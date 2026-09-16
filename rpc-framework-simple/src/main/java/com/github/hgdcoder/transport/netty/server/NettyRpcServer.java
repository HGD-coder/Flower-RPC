package com.github.hgdcoder.transport.netty.server;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.remoting.codec.NettyRpcFrameDecoder;
import com.github.hgdcoder.remoting.codec.NettyRpcMessageDecoder;
import com.github.hgdcoder.remoting.codec.NettyRpcMessageEncoder;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.handler.RpcRequestHandler;
import com.github.hgdcoder.transport.netty.handler.NettyRpcHeartbeatHandler;
import com.github.hgdcoder.utils.concurrent.threadpool.CustomThreadPoolConfig;
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
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Netty RPC 服务端：I/O 线程只负责网络事件，反射调用由独立业务线程组执行。
 * 启动后为每条连接装配“切帧 -> 解码 -> 编码 -> 业务处理”Pipeline，
 * 最后一个处理器被转交到业务线程组，实现网络 I/O 与反射调用的隔离。
 */
public final class NettyRpcServer implements AutoCloseable {
    private static final int SERVER_BACKLOG = 1024;

    /**
     * 对外发布地址。
     *
     * 这个地址会写入注册中心，客户端最终连接它。
     */
    private final String serverHost;

    /**
     * Netty 在当前机器上真正绑定的地址。
     *
     * 可以使用 0.0.0.0 监听全部 IPv4 网卡。
     */
    private final String bindHost;

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

    /**
     * 执行短耗时、低延迟业务。
     */
    private final ThreadPoolExecutor fastBusinessExecutor;

    /**
     * 执行阻塞或长耗时业务。
     */
    private final ThreadPoolExecutor slowBusinessExecutor;

    /**
     * 服务端可信的请求分类规则。
     */
    private final Predicate<RpcRequest> slowRequestPredicate;

    // 控制 start/close 的一次性生命周期；关闭后不允许重新绑定。
    private final AtomicBoolean closed = new AtomicBoolean();

    // 绑定成功后可见的监听 Channel，volatile 让 getPort 可读取实际分配的端口。
    private volatile Channel serverChannel;

    public NettyRpcServer(
            RpcFrameworkConfig config,
            ServiceProvider serviceProvider,
            Predicate<RpcRequest> slowRequestPredicate
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

        this.slowRequestPredicate = Objects.requireNonNull(
                slowRequestPredicate,
                "slowRequestPredicate must not be null"
        );

        this.port = config.getServerPort();
        this.serverHost = config.getServerHost();
        this.bindHost = config.getBindHost();
        this.heartbeatTimeoutSeconds =
                config.getHeartbeatTimeoutSeconds();
        this.serviceProvider = serviceProvider;

        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();

        int processors =
                Runtime.getRuntime().availableProcessors();

        this.fastBusinessExecutor = createBusinessExecutor(
                Math.max(2, processors),
                128,
                "flower-rpc-fast"
        );

        this.slowBusinessExecutor = createBusinessExecutor(
                Math.max(4, processors * 2),
                256,
                "flower-rpc-slow"
        );
    }

    public NettyRpcServer(
            RpcFrameworkConfig config,
            ServiceProvider serviceProvider
    ) {
        /*
         * 默认从 ServiceProvider 查询服务端方法分类。
         *
         * 使用 lambda 而不是立即创建方法引用，
         * 可以让三参数构造器继续统一处理 null 校验。
         */
        this(
                config,
                serviceProvider,
                request -> serviceProvider != null
                                    && serviceProvider.isSlowRequest(request)
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

    private static ThreadPoolExecutor createBusinessExecutor(
            int threadCount,
            int queueCapacity,
            String threadNamePrefix
    ) {
        CustomThreadPoolConfig poolConfig = CustomThreadPoolConfig.builder()
                        /*
                         * 固定大小线程池，行为更容易理解和压测。
                         */
                        .corePoolSize(threadCount)
                        .maximumPoolSize(threadCount)
                        .queueCapacity(queueCapacity)
                        .build();

        return ThreadPoolFactoryUtil.createThreadPool(
                poolConfig,
                threadNamePrefix,
                false,
                /*
                 * 队列满时抛出 RejectedExecutionException，
                 * 由 NettyRpcServerHandler 返回 RESOURCE_EXHAUSTED。
                 */
                new ThreadPoolExecutor.AbortPolicy()
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
                new RpcRequestHandler(serviceProvider),
                fastBusinessExecutor,
                slowBusinessExecutor,
                slowRequestPredicate
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
                                "rpcServerHandler",
                                serverHandler
                        );
                    }
                });

        try{
            /*
             * Netty 绑定的是 bindHost。
             *
             * 例如 bindHost=0.0.0.0，表示接受发送到
             * 当前机器任意 IPv4 网卡的连接。
             */
            serverChannel = bootstrap.bind(bindHost, port)
                    .syncUninterruptibly()
                    .channel();

            /*
             * 发布地址使用 serverHost，而不是 bindHost。
             *
             * createUnresolved() 不会在这里执行 DNS 查询，
             * 并且能够原样保留用户配置的域名。
             */
            InetSocketAddress address =
                    InetSocketAddress.createUnresolved(
                    serverHost,
                    getPort()
            );

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


        ThreadPoolFactoryUtil.shutdownGracefully(
                fastBusinessExecutor,
                5,
                TimeUnit.SECONDS
        );

        ThreadPoolFactoryUtil.shutdownGracefully(
                slowBusinessExecutor,
                5,
                TimeUnit.SECONDS
        );
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
