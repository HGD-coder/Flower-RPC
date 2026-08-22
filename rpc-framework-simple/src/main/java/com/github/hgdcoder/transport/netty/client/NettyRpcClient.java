package com.github.hgdcoder.transport.netty.client;

import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.remoting.codec.NettyRpcFrameDecoder;
import com.github.hgdcoder.remoting.codec.NettyRpcMessageDecoder;
import com.github.hgdcoder.remoting.codec.NettyRpcMessageEncoder;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcMessage;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.RpcRequestTransport;
import com.github.hgdcoder.transport.netty.handler.NettyRpcHeartbeatHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;


import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于 Netty 的同步 RPC 客户端；网络收发异步，公共传输接口保持同步。
 * 调用链为: 服务发现 -> 连接提供者 -> pending 登记 -> Pipeline 出站编码 -> 网络发送，
 * 响应则经入站解码和客户端处理器完成 Future，最后由本类等待并返回结果。
 */
public class NettyRpcClient implements RpcRequestTransport,AutoCloseable {
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 5000;

    // 调用时查询目标地址；具体注册中心实现不属于 Netty 客户端的生命周期。
    private final ServiceDiscovery serviceDiscovery;
    // 每个 pending 请求的等待上限，超时任务由对应 Channel 的 EventLoop 执行
    private final int requestTimeoutMillis;
    // 客户端 I/O 线程组，close 后不可重启，因此整个 NettyRpcClient 也不可重用。
    private final EventLoopGroup eventLoopGroup;
    // 跨连接共享的请求号到 Future 映射，负责唤醒同步等待的业务调用线程。
    private final UnprocessedRequests unprocessedRequests = new UnprocessedRequests();
    // 连接缓存和并发建连占位逻辑由此封装。
    private final NettyChannelProvider channelProvider;
    // 仅生成协议层 int 请求号；重复检测仍由 pending 表承担，避免回绕时覆盖未完成调用。
    private final AtomicInteger requestIdGenerator = new AtomicInteger();
    // 一旦置为 true，拒绝新调用，并在关闭顺序中防止重复释放资源。
    private final AtomicBoolean closed = new AtomicBoolean();
    // 读锁保护一次请求从取连接到登记和写出的关键段；写锁让关闭等待该段结束。
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    public NettyRpcClient(ServiceDiscovery serviceDiscovery){
        this(serviceDiscovery,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    /**
     * 创建客户端并配置真实连接使用的 Pipeline。
     * 入站按“切帧 -> 解码 -> 完成 pending”执行，出站时 Netty 会反向经过消息编码器。
     * 此构造器在包内可见，供本地端到端测试缩短连接和请求超时。
     */
    NettyRpcClient(ServiceDiscovery serviceDiscovery,
                   int connectTimeoutMills,
                   int requestTimeoutMills) {
        if(serviceDiscovery == null){
            throw new IllegalArgumentException("serviceDiscovery must not be null");
        }
        if(connectTimeoutMills <=0 || requestTimeoutMills <=0){
            throw new IllegalArgumentException("timeouts must be positive");
        }

        this.serviceDiscovery = serviceDiscovery;
        this.requestTimeoutMillis = requestTimeoutMills;
        //NioEventLoopGroup 是需要显式关闭的重量级资源，所以先验证构造参数，再创建更合适。
        this.eventLoopGroup = new NioEventLoopGroup();

        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,connectTimeoutMills)
                .option(ChannelOption.TCP_NODELAY,true)
                .handler(new ChannelInitializer<SocketChannel>(){
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        /*
                         * IdleStateHandler 不处理 RPC 业务数据，只统计这条连接多久没有读写。
                         *
                         * readerIdleTime：
                         * 15 秒没有收到服务端任何数据，触发 READER_IDLE。
                         *
                         * writerIdleTime：
                         * 5 秒没有向服务端写任何数据，触发 WRITER_IDLE。
                         *
                         * 事件会继续沿 Pipeline 向后传播，最终由
                         * NettyRpcClientHandler.userEventTriggered() 处理。
                         */
                        channel.pipeline().addLast(
                                "clentIdleStateHandler",
                                new IdleStateHandler(
                                        RpcConstants.HEARTBEAT_TIMEOUT_SECONDS,
                                        RpcConstants.HEARTBEAT_INTERVAL_SECONDS,
                                        0,
                                        TimeUnit.SECONDS
                                )
                        );

                        // 入站按 addLast 顺序传播；出站从尾到头传播，因此编码器会在写入网络前执行。
                        channel.pipeline().addLast(new NettyRpcFrameDecoder());
                        channel.pipeline().addLast(new NettyRpcMessageDecoder());
                        channel.pipeline().addLast(new NettyRpcMessageEncoder());
                        /*
                         * 心跳在 I/O EventLoop 中处理，并在业务响应处理器之前消费 PONG。
                         * 普通 RpcResponse 会继续传播给后面的 NettyRpcClientHandler。
                         */
                        channel.pipeline().addLast(
                                "rpcHeartbeatHandler",
                                NettyRpcHeartbeatHandler.forClient()
                        );
                        channel.pipeline().addLast(
                                new NettyRpcClientHandler(unprocessedRequests)
                        );
                    }
                });
        this.channelProvider = new NettyChannelProvider(bootstrap,connectTimeoutMills);
    }


    /**
     * 发送一次请求并同步等待响应，保持 RpcRequestTransport 对调用方的同步语义。
     *
     * @param rpcRequest 业务层请求，服务发现使用它定位服务，消息体直接携带它
     * @return 服务端返回的 RpcResponse
     * 调用时先登记 pending 再 writeAndFlush，避免极快响应在登记前到达而丢失。
     */
    @Override
    public Object sendRpcRequest(RpcRequest rpcRequest) {
        if(closed.get()){
            throw new IllegalStateException("Netty RPC client is closed");
        }
        InetSocketAddress address = serviceDiscovery.lookupService(rpcRequest);
        CompletableFuture<RpcResponse<?>> responseFuture;
        int requestId;
        lifecycleLock.readLock().lock();
        try{
            if(closed.get()){
                throw new IllegalStateException("Netty RPC client is closed");
            }
            Channel channel = channelProvider.getChannel(address);

            do{
                requestId = nextRequestId();
                responseFuture = unprocessedRequests.register(
                        requestId,
                        channel,
                        requestTimeoutMillis
                );
            } while (responseFuture == null);

            RpcMessage requestMessage = RpcMessage.builder()
                    .messageType(RpcConstants.REQUEST_TYPE)
                    .codec(RpcConstants.DEFAULT_CODEC)
                    // V12 默认使用 GZIP；服务端会沿用该字段压缩响应。
                    .compress(RpcConstants.DEFAULT_COMPRESS)
                    .requestId(requestId)
                    .data(rpcRequest)
                    .build();

            final int currentRequestId = requestId;
            ChannelFuture writeFuture = channel.writeAndFlush(requestMessage);
            writeFuture.addListener(future->{
                if(!future.isSuccess()){
                    unprocessedRequests.fail(currentRequestId,future.cause());
                }
            });
        }finally {
            lifecycleLock.readLock().unlock();
        }

        try{
            return responseFuture.get();
        }catch(InterruptedException e){
            unprocessedRequests.fail(requestId,e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("RPC request interrupted: " + address, e);
        }catch(ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("Send RPC request failed: " + address, cause);
        }
    }

    /**
     * 生成非零协议请求号；到达 int 上界后从 1 回绕，调用方会通过 register 处理罕见的占用冲突。
     */
    private int nextRequestId() {
        return requestIdGenerator.updateAndGet(
                value -> value == Integer.MAX_VALUE ? 1 : value + 1
        );
    }

    /**
     * 关闭全部地址连接，但保留 EventLoop，后续请求仍可重新按需建连。
     */
    public void closeConnections() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed.get()) {
                return;
            }
            unprocessedRequests.failAll(
                    new IllegalStateException("RPC connections were closed"));
            channelProvider.closeConnections();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 清零连接创建与复用计数，仅影响观测结果，不关闭连接也不影响请求。
     */
    public void resetConnectionStatistics() {
        channelProvider.resetStatistics();
    }

    /**
     * 读取当前连接统计快照；并发请求可能继续改变下一次读取的数值。
     */
    public ConnectionStatistics getConnectionStatistics() {
        return channelProvider.statistics();
    }

    public long getCreatedConnectionCount() {
        return getConnectionStatistics().getCreatedConnections();
    }

    public long getReusedConnectionCount() {
        return getConnectionStatistics().getReusedConnections();
    }

    int getPendingRequestCount() {
        return unprocessedRequests.size();
    }

    /**
     * 永久关闭客户端。
     * 关闭顺序为: 阻止新请求 -> 失败并清理 pending -> 关闭缓存 Channel -> 停止 EventLoop，
     * 这样等待线程不会在 I/O 线程已退出后遗留未完成的 Future。
     */
    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try{
            if(!closed.compareAndSet(false, true)){
                return;
            }
            unprocessedRequests.failAll(new IllegalStateException("Netty RPC client is closed"));
            channelProvider.close();
        }finally {
            lifecycleLock.writeLock().unlock();
        }
        eventLoopGroup.shutdownGracefully(0,5, TimeUnit.SECONDS)
                .syncUninterruptibly();
    }
}
