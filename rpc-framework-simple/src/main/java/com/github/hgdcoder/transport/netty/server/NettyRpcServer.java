package com.github.hgdcoder.transport.netty.server;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.remoting.codec.NettyRpcFrameDecoder;
import com.github.hgdcoder.remoting.codec.NettyRpcMessageDecoder;
import com.github.hgdcoder.remoting.codec.NettyRpcMessageEncoder;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.handler.RpcRequestHandler;
import com.github.hgdcoder.transport.netty.handler.NettyRpcHeartbeatHandler;
import com.github.hgdcoder.utils.concurrent.threadpool.ThreadPoolFactoryUtil;
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

    /** 配置中的服务端主机地址，同时用于监听和发布。 */
    private final String serverHost;

    /**
     * 当前服务端进入发布阶段时使用的地址。
     * 关闭时使用同一个地址注销，包括清理部分发布失败的情况。
     */
    private volatile InetSocketAddress publishedAddress;

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
        this.serverHost = config.getServerHost();
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
                        ),
                        ThreadPoolFactoryUtil.createThreadFactory(
                                "flower-rpc-netty-business",
                                false
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
            /*
             * 同步等待端口绑定成功。
             * serverHost 指定监听地址，port 指定监听端口。
             */
            serverChannel = bootstrap.bind(serverHost, port)
                    .syncUninterruptibly()
                    .channel();

            /*
             * 配置端口可能为 0，表示让操作系统分配空闲端口。
             * 因此发布时必须使用 getPort() 返回的实际端口。
             */
            InetSocketAddress address =
                    new InetSocketAddress(serverHost, getPort());

            /*
             * 发布前先保存地址。
             * 即使发布中途失败，close() 仍然知道要清理哪个地址。
             */
            publishedAddress = address;

            // 此时监听端口已经就绪，才允许客户端发现这些服务。
            serviceProvider.publishAllServices(address);
        }catch(RuntimeException startFailure) {
            try{
                // 绑定失败或发布失败，都需要清理已经创建的资源。
                close();
            }catch (RuntimeException cleanupFailure){
                // 保留启动失败作为主要原因，附加清理阶段的异常。
                startFailure.addSuppressed(cleanupFailure);
            }

            throw new RuntimeException(
                    "Netty RPC server start failed: " + port,
                    startFailure
            );
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
        // 只有第一次关闭负责执行清理，避免重复释放。
        if(!closed.compareAndSet(false,true)){
            return;
        }

        RuntimeException closeFailure = null;

        try {
            Channel channel = serverChannel;
            if (channel != null) {
                // 关闭监听 Channel，停止接受新连接。
                channel.close().syncUninterruptibly();
            }
        } catch (RuntimeException e) {
            // 先记录异常，继续执行后续清理。
            closeFailure = e;
        }

        InetSocketAddress address = publishedAddress;
        if (address != null) {
            try {
                // 从注册中心删除当前服务端发布的服务地址。
                serviceProvider.unpublishAllServices(address);

                // 全部注销成功后，清空服务端记录的发布地址。
                publishedAddress = null;
            } catch (RuntimeException e) {
                // 注销失败不能阻止 Netty 线程资源的释放。
                if (closeFailure == null) {
                    closeFailure = e;
                } else {
                    closeFailure.addSuppressed(e);
                }
            }
        }

        // 先关闭业务执行器，随后关闭连接 I/O 和接入线程组。
        businessGroup.shutdownGracefully(0,5, TimeUnit.SECONDS)
                .syncUninterruptibly();
        workerGroup.shutdownGracefully(0,5, TimeUnit.SECONDS)
                .syncUninterruptibly();
        bossGroup.shutdownGracefully(0,5, TimeUnit.SECONDS)
                .syncUninterruptibly();

        // 完成后续清理后，再把前面记录的异常交给调用方。
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

}
