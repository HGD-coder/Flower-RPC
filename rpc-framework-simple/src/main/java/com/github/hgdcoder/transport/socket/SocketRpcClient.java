package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.RpcRequestTransport;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于阻塞 Socket 的 RPC 客户端。
 *
 * V7 的核心变化：
 * 从“每个线程只有一条连接”改为“每个线程、每个服务地址一条连接”。
 */
public class SocketRpcClient implements RpcRequestTransport,AutoCloseable {
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 5000;

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

    private final AtomicBoolean closed = new AtomicBoolean();

    /** close 时用于唤醒尚未完成的 BIO 调用，包括仍在队列中的任务。 */
    private final Set<CompletableFuture<RpcResponse<Object>>> activeRequests =
            ConcurrentHashMap.newKeySet();

    /**
     * V4 usage:
     * new SocketRpcClient(new FileServiceDiscovery())
     */
    public SocketRpcClient(ServiceDiscovery serviceDiscovery) {
        if (serviceDiscovery == null) {
            throw new IllegalArgumentException("serviceDiscovery must not be null");
        }
        this.serviceDiscovery = serviceDiscovery;
        this.ioExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                new SocketIoThreadFactory()
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
            resultFuture.completeExceptionally(e);
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

        connection = new SocketConnection(
                address,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_READ_TIMEOUT_MILLIS
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
            ioExecutor.shutdownNow();
        }
    }

    /** JDK 8 兼容版失败 Future。 */
    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    /** 创建守护线程，并保证线程结束时释放它私有的地址级连接缓存。 */
    private final class SocketIoThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable worker) {
            Thread thread = new Thread(() -> {
                try {
                    worker.run();
                } finally {
                    connectionProvider.closeCurrentThreadConnection();
                }
            }, "flower-rpc-bio-client-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
