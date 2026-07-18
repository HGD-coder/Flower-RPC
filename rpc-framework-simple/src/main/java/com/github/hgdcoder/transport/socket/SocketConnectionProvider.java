package com.github.hgdcoder.transport.socket;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

final class SocketConnectionProvider {
    /**
     * 每个调用线程拥有一张独立的地址到连接映射表。
     */
    private final ThreadLocal<Map<String,SocketConnection>> connectionMapHolder=
            ThreadLocal.withInitial(
                    ()->new HashMap<String,SocketConnection>()
            );

    /**
     * 记录本轮实际创建了多少条连接。
     */
    private final LongAdder createdConnectionCount = new LongAdder();

    /**
     * 记录有多少请求复用了已有连接
     */
    private final LongAdder reusedConnectionCount = new LongAdder();

    /**
     * 获取当前线程到指定地址的可用连接
     */
    SocketConnection get(InetSocketAddress address){
        String key = buildKey(address);
        Map<String,SocketConnection> connectionMap = connectionMapHolder.get();

        SocketConnection connection = connectionMap.get(key);

        if(connection == null){
            return null;
        }

        if(connection.isAvailable()){
            reusedConnectionCount.increment();
            return connection;
        }

        //本地已经判断连接不可用，即使删除并释放资源
        connectionMap.remove(key);
        connection.close();
        return null;
    }

    /**
     * 保存当前线程新创建的连接
     */
    void set(InetSocketAddress address,SocketConnection connection){
        String key = buildKey(address);
        Map<String,SocketConnection> connectionMap = connectionMapHolder.get();

        //它会把 key 和 value 保存到 Map 中，同时返回这个 key 原来对应的旧值。
        SocketConnection oldConnection = connectionMap.put(key,connection);

        // 正常情况下旧连接已经被移除，这里是额外的资源保护。
        if(oldConnection != null && oldConnection!=connection){
            oldConnection.close();
        }

        createdConnectionCount.increment();
    }

    /**
     * 删除并关闭当前线程到指定地址的连接。
     *
     * 某个节点调用失败时，只关闭这个节点的连接，
     * 不影响当前线程已经建立的其他节点连接。
     */
    void remove(InetSocketAddress address){
        String key = buildKey(address);
        Map<String,SocketConnection> connectionMap = connectionMapHolder.get();

        SocketConnection connection = connectionMap.remove(key);
        if(connection != null){
            connection.close();
        }
    }

    /**
     * 关闭当前线程持有的全部连接。
     *
     * Benchmark 的每个工作线程退出前都必须调用该方法，
     * 否则线程池结束后可能遗留 Socket 资源。
     */
    void closeCurrentThreadConnection(){
        Map<String,SocketConnection> connectionMap = connectionMapHolder.get();

        for (SocketConnection connection : connectionMap.values()) {
            connection.close();
        }

        connectionMap.clear();
        connectionMapHolder.remove();
    }

    long getCreatedConnectionCount() {
        return createdConnectionCount.sum();
    }

    long getReusedConnectionCount() {
        return reusedConnectionCount.sum();
    }

    /**
     * Benchmark 预热结束后调用，让正式测试从 0 开始计数。
     *
     * reset() 不适合在并发压测进行期间调用；
     * 当前只在工作线程启动前调用，所以是安全的。
     */
    void resetStatistics() {
        createdConnectionCount.reset();
        reusedConnectionCount.reset();
    }

    /**
     * 使用带方括号的形式生成稳定地址键，也能区分 IPv6 地址和端口。
     *
     * 示例：
     * [127.0.0.1]:9998
     */
    private String buildKey(InetSocketAddress address) {
        return "[" + address.getHostString() + "]:" + address.getPort();
    }
}
