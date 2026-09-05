package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.enums.RpcStatusCode;
import com.github.hgdcoder.exception.RpcException;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.RpcRequestTransport;
import com.github.hgdcoder.utils.RuntimeUtil;
import com.github.hgdcoder.utils.concurrent.threadpool.CustomThreadPoolConfig;
import com.github.hgdcoder.utils.concurrent.threadpool.ThreadPoolFactoryUtil;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于阻塞 Socket 的 RPC 客户端。
 *
 * V7 的核心变化：
 * 从“每个线程只有一条连接”改为“每个线程、每个服务地址一条连接”。
 */
public class SocketRpcClient implements RpcRequestTransport,AutoCloseable {

    /**
     * 根据 rpcServiceName 从 ZooKeeper 地址缓存中发现服务，
     * 再通过负载均衡选择一个服务提供者地址。
     */
    private final ServiceDiscovery serviceDiscovery;

    /**
     * 管理当前客户端的地址级连接缓存。
     */
    private final SocketConnectionProvider connectionProvider =
            new SocketConnectionProvider();

    /**
     * BIO 的 read/write 会阻塞线程，因此不能直接在调用方线程执行。
     * 这里用独立工作线程包装阻塞操作，对外仍返回异步 Future。
     */
    private final ExecutorService ioExecutor;

    /**
     * 建立 Socket 连接的超时时间。
     *
     * <p>来自创建客户端时传入的配置快照。</p>
     */
    private final int connectTimeoutMillis;

    /**
     * 等待 RPC 响应的 Socket 读取超时时间。
     *
     * <p>超过该时间仍未收到响应时，
     * Socket 的读取操作会抛出 SocketTimeoutException。</p>
     */
    private final int requestTimeoutMillis;

    private final AtomicBoolean closed = new AtomicBoolean();

    /** close 时用于唤醒尚未完成的 BIO 调用，包括仍在队列中的任务。 */
    private final Set<CompletableFuture<RpcResponse<Object>>> activeRequests =
            ConcurrentHashMap.newKeySet();

    /**
     * 兼容之前直接传入 ServiceDiscovery 的写法。
     *
     * <p>没有显式提供配置时，使用框架默认配置。</p>
     */
    public SocketRpcClient(
            ServiceDiscovery serviceDiscovery
    ) {
        this(
                serviceDiscovery,
                RpcFrameworkConfig.defaults()
        );
    }

    /**
     * RpcExtensionFactory 使用的统一 public 构造器。
     *
     * <p>它的参数签名必须与工厂传给 newExtension()
     * 的 parameterTypes 完全一致。</p>
     */
    public SocketRpcClient(
            ServiceDiscovery serviceDiscovery,
            RpcFrameworkConfig config
    ) {
        this(
                serviceDiscovery,
                config,
                defaultIoPoolConfig()
        );
    }

    /**
     * 测试专用构造器。
     *
     * <p>测试可以传入容量非常小的线程池配置，
     * 从而稳定地制造队列满和背压场景。</p>
     */
    SocketRpcClient(
            ServiceDiscovery serviceDiscovery,
            CustomThreadPoolConfig poolConfig
    ) {
        this(
                serviceDiscovery,
                RpcFrameworkConfig.defaults(),
                poolConfig
        );
    }

    /**
     * 所有构造入口最终汇聚到这里。
     */
    private SocketRpcClient(
            ServiceDiscovery serviceDiscovery,
            RpcFrameworkConfig config,
            CustomThreadPoolConfig poolConfig
    ) {
        if (serviceDiscovery == null) {
            throw new IllegalArgumentException(
                    "serviceDiscovery must not be null"
            );
        }

        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null"
            );
        }

        this.serviceDiscovery = serviceDiscovery;

        /*
         * 保存配置值。
         *
         * 后续每次创建 SocketConnection 时使用这两个值，
         * 不再使用类中写死的超时常量。
         */
        this.connectTimeoutMillis =
                config.getConnectTimeoutMillis();

        this.requestTimeoutMillis =
                config.getRequestTimeoutMillis();

        ThreadFactory namedFactory =
                ThreadPoolFactoryUtil.createThreadFactory(
                        "flower-rpc-bio-client",
                        true
                );

        /*
         * 每个 BIO 工作线程退出时，
         * 清理该线程 ThreadLocal 中保存的 Socket。
         */
        ThreadFactory cleanupFactory =
                worker -> namedFactory.newThread(() -> {
                    try {
                        worker.run();
                    } finally {
                        connectionProvider
                                .closeCurrentThreadConnection();
                    }
                });

        this.ioExecutor =
                ThreadPoolFactoryUtil.createThreadPool(
                        poolConfig,
                        cleanupFactory,
                        new ThreadPoolExecutor.AbortPolicy()
                );
    }

    /**
     * Sends one RPC request.
     *
     * Call chain:
     * RpcClientProxy.invoke(...)
     * -> SocketRpcClient.sendRpcRequest(...)
     * -> serviceDiscovery.lookupService(...)
     * -> SocketConnection.send(...)
     */
    @Override
    public CompletableFuture<RpcResponse<Object>> sendRpcRequest(RpcRequest rpcRequest) {
        if (closed.get()) {
            return failedFuture(new IllegalStateException("Socket RPC client is closed"));
        }

        CompletableFuture<RpcResponse<Object>> resultFuture = new CompletableFuture<>();
        activeRequests.add(resultFuture);
        resultFuture.whenComplete((response, throwable) ->
                activeRequests.remove(resultFuture));
        try {
            ioExecutor.execute(() -> sendBlocking(rpcRequest, resultFuture));
        } catch (RejectedExecutionException e) {
            // 有界队列已满：快速失败，不能继续堆积请求直至内存耗尽。
            resultFuture.completeExceptionally(new RpcException(
                    RpcStatusCode.RESOURCE_EXHAUSTED,
                    rpcRequest == null ? null : rpcRequest.getRequestId(),
                    "BIO client request queue is full",
                    e
            ));
        }
        return resultFuture;
    }

    /** 真正的阻塞 Socket 调用只在专用 BIO 工作线程中执行。 */
    @SuppressWarnings("unchecked")
    private void sendBlocking(
            RpcRequest rpcRequest,
            CompletableFuture<RpcResponse<Object>> resultFuture
    ) {
        InetSocketAddress address = null;
        try {
            if (closed.get()) {
                throw new IllegalStateException("Socket RPC client is closed");
            }
            address = serviceDiscovery.lookupService(rpcRequest);
            SocketConnection connection = getOrCreateConnection(address);
            Object response = connection.send(rpcRequest);
            resultFuture.complete((RpcResponse<Object>) response);
        } catch (Exception e) {
            // 当前地址失败时只删除当前工作线程到该地址的连接。
            if (address != null) {
                connectionProvider.remove(address);
            }
            resultFuture.completeExceptionally(e);
        }
    }

    /**
     * 优先复用当前线程已经建立的地址连接。
     */
    private SocketConnection getOrCreateConnection(InetSocketAddress address) {
        SocketConnection connection = connectionProvider.get(address);

        if(connection != null) {
            return connection;
        }

        /*
         * 第一次访问该地址时创建连接。
         *
         * connectTimeoutMillis 控制建立 TCP 连接的等待时间。
         * requestTimeoutMillis 控制读取 RPC 响应的等待时间。
         */
        connection = new SocketConnection(
                address,
                connectTimeoutMillis,
                requestTimeoutMillis
        );

        connectionProvider.set(address, connection);
        return connection;
    }

    /**
     * 关闭当前调用线程持有的所有服务地址连接。
     *
     * 注意它只能关闭当前线程的 ThreadLocal 连接，
     * 因此 Benchmark 工作线程必须各自在 finally 中调用。
     */
    public void closeCurrentThreadConnections() {
        connectionProvider.closeCurrentThreadConnection();
    }

    public long getCreatedConnectionCount(){
        return connectionProvider.getCreatedConnectionCount();
    }

    public long getReusedConnectionCount(){
        return connectionProvider.getReusedConnectionCount();
    }

    /**
     * 清空预热阶段产生的连接统计
     */
    public void resetConnectionStatistics(){
        connectionProvider.resetStatistics();
    }

    /**
     * 停止接收新请求。每个 BIO 工作线程退出时，ThreadFactory 包装层会关闭
     * 该线程 ThreadLocal 中保存的全部 Socket 连接。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            IllegalStateException cause =
                    new IllegalStateException("Socket RPC client is closed");
            for (CompletableFuture<RpcResponse<Object>> request : activeRequests) {
                request.completeExceptionally(cause);
            }
            ThreadPoolFactoryUtil.shutdownGracefully(
                    ioExecutor,
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    /** JDK 8 兼容版失败 Future。 */
    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    /** 根据 CPU 数量生成 BIO 客户端的默认线程池配置。 */
    private static CustomThreadPoolConfig defaultIoPoolConfig() {
        int cpus = RuntimeUtil.cpus();
        int corePoolSize = Math.max(2, Math.min(4, cpus));
        int maximumPoolSize = Math.max(corePoolSize, cpus * 2);
        return CustomThreadPoolConfig.builder()
                .corePoolSize(corePoolSize)
                .maximumPoolSize(maximumPoolSize)
                .keepAliveTime(60L)
                .timeUnit(TimeUnit.SECONDS)
                .queueCapacity(256)
                .build();
    }
}
