package com.github.hgdcoder.transport.netty.server;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.remoting.codec.NettyRpcFrameDecoder;
import com.github.hgdcoder.remoting.codec.NettyRpcMessageDecoder;
import com.github.hgdcoder.remoting.codec.NettyRpcMessageEncoder;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.handler.RpcRequestHandler;
import com.github.hgdcoder.transport.netty.handler.NettyRpcHeartbeatHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty RPC 服务端：I/O 线程只负责网络事件，反射调用由独立业务线程组执行。
 * 启动后为每条连接装配“切帧 -> 解码 -> 编码 -> 业务处理”Pipeline，
 * 最后一个处理器被转交到业务线程组，实现网络 I/O 与反射调用的隔离。
 */
public final class NettyRpcServer implements AutoCloseable {
    private static final int SERVER_BACKLOG = 1024;

    // 构造时指定的监听端口；0表示由操作系统选择空闲端口
    private final int port;

    private final int heartbeatTimeoutSeconds;

    // 服务查找和调用的来源，在服务端整个生命周期内保持不变
    private final ServiceProvider serviceProvider;

    // 只接收新连接的线程组，服务关闭时最后释放。
    private final EventLoopGroup bossGroup;

    // 处理已建立连接的读写事件的 I/O 线程组。
    private final EventLoopGroup workerGroup;

    // 专门执行可能阻塞的业务调用，避免占用 workerGroup 的 I/O 线程。
    private final DefaultEventExecutorGroup businessGroup;

    // 控制 start/close 的一次性生命周期；关闭后不允许重新绑定。
    private final AtomicBoolean closed = new AtomicBoolean();

    // 绑定成功后可见的监听 Channel，volatile 让 getPort 可读取实际分配的端口。
    private volatile Channel serverChannel;

    public NettyRpcServer(
            RpcFrameworkConfig config,
            ServiceProvider serviceProvider
    ) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null"
            );
        }
        if (serviceProvider == null) {
            throw new IllegalArgumentException(
                    "serviceProvider must not be null"
            );
        }

        this.port = config.getServerPort();
        this.heartbeatTimeoutSeconds =
                config.getHeartbeatTimeoutSeconds();
        this.serviceProvider = serviceProvider;

        // 参数校验通过后才创建重量级线程资源。
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.businessGroup =
                new DefaultEventExecutorGroup(
                        Math.max(
                                2,
                                Runtime.getRuntime().availableProcessors()
                        )
                );
    }

    public NettyRpcServer(
            int port,
            ServiceProvider serviceProvider
    ) {
        this(
                RpcFrameworkConfig.load()
                        .toBuilder()
                        .serverPort(port)
                        .build(),
                serviceProvider
        );
    }

    /**
     * 完成端口绑定后返回；Netty 线程会继续服务，便于测试和显式关闭。
     */
    /**
     * 组装服务端 Pipeline 并同步完成端口绑定。
     * 该方法只等待 bind 成功，不等待服务停止；重复调用在同一实例上是幂等的。
     */
    public synchronized void start() {
        if(closed.get()) {
            throw new IllegalStateException("Netty RPC server is closed");
        }
        if(serverChannel != null) {
            return;
        }

        NettyRpcServerHandler serverHandler = new NettyRpcServerHandler(
                new RpcRequestHandler(serviceProvider)
        );
        /*
         * 心跳处理器不保存连接级状态，可以被全部 SocketChannel 共享。
         * 它留在 worker I/O 线程执行，不进入可能被慢业务占满的 businessGroup。
         */
        NettyRpcHeartbeatHandler heartbeatHandler =
                NettyRpcHeartbeatHandler.forServer();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, SERVER_BACKLOG)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        /*
                         * 服务端只关心读空闲：15 秒没有收到业务请求或 PING，
                         * 就产生 READER_IDLE，交给后面的心跳 Handler 关闭连接。
                         */
                        channel.pipeline().addLast(
                                "serverIdleStateHandler",
                                new IdleStateHandler(
                                        heartbeatTimeoutSeconds,
                                        0,
                                        0,
                                        TimeUnit.SECONDS
                                )
                        );
                        // 入站依次切帧、反序列化并进入业务线程；出站响应反向经过编码器。
                        channel.pipeline().addLast(new NettyRpcFrameDecoder());
                        channel.pipeline().addLast(new NettyRpcMessageDecoder());
                        channel.pipeline().addLast(new NettyRpcMessageEncoder());
                        channel.pipeline().addLast(
                                "rpcHeartbeatHandler",
                                heartbeatHandler
                        );
                        channel.pipeline().addLast(
                                businessGroup,
                                "rpcServerHandler",
                                serverHandler
                        );
                    }
                });

        try{
            serverChannel = bootstrap.bind(port).syncUninterruptibly().channel();
        }catch(RuntimeException e) {
            close();
            throw new RuntimeException("Netty RPC server bind failed:" + port, e);
        }
    }

    /**
     * 返回实际监听端口。端口为 0 时仅在 start 成功后才能取得系统分配的端口。
     */
    public int getPort() {
        Channel channel = serverChannel;
        if(channel == null) {
            return port;
        }
        return ((InetSocketAddress)channel.localAddress()).getPort();
    }


    /**
     * 关闭监听端口并按依赖方向释放线程组。
     * 先停止接收新连接，再结束业务任务，随后停止连接 I/O，最后停止接入线程，
     * 减少关闭期间新工作进入已释放执行器的机会。
     */
    @Override
    public synchronized void close() {
        if(!closed.compareAndSet(false,true)){
            return;
        }
        Channel channel = serverChannel;
        if(channel != null) {
            channel.close().syncUninterruptibly();
        }
        businessGroup.shutdownGracefully(0,5, TimeUnit.SECONDS)
                .syncUninterruptibly();
        workerGroup.shutdownGracefully(0,5, TimeUnit.SECONDS)
                .syncUninterruptibly();
        bossGroup.shutdownGracefully(0,5, TimeUnit.SECONDS)
                .syncUninterruptibly();
    }

}
