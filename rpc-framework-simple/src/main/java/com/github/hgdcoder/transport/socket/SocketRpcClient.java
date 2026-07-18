package com.github.hgdcoder.transport.socket;

import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.transport.RpcRequestTransport;

import java.net.InetSocketAddress;

/**
 * 基于阻塞 Socket 的 RPC 客户端。
 *
 * V7 的核心变化：
 * 从“每个线程只有一条连接”改为“每个线程、每个服务地址一条连接”。
 */
public class SocketRpcClient implements RpcRequestTransport {
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
     * V4 usage:
     * new SocketRpcClient(new FileServiceDiscovery())
     */
    public SocketRpcClient(ServiceDiscovery serviceDiscovery) {
        this.serviceDiscovery = serviceDiscovery;
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
    public Object sendRpcRequest(RpcRequest rpcRequest) {
        InetSocketAddress address = serviceDiscovery.lookupService(rpcRequest);
        SocketConnection connection = getOrCreateConnection(address);

        try {
            return connection.send(rpcRequest);
        } catch (Exception e) {
            /*
             * 当前地址调用失败，只删除该地址对应的连接。
             *
             * 例如 9999 失败时：
             * 删除 Socket-9999
             * 保留 Socket-9998
             * 保留 Socket-10000
             */
            connectionProvider.remove(address);

            throw new RuntimeException("Send rpc request failed:" + address, e);
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

}
