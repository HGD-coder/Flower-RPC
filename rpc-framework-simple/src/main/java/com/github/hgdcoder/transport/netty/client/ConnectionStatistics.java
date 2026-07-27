package com.github.hgdcoder.transport.netty.client;

/**
 * 某个 NettyRpcClient 自创建以来的连接复用快照。
 * 由 NettyChannelProvider 在查询时创建，位于客户端连接管理调用链的观测端，
 * 只承载已经汇总的数值，不参与建连或复用决策。
 */
public final class ConnectionStatistics {
    // 快照创建前成功建立的物理连接数，不会随快照对象后续变化。
    private final long createdConnections;
    // 快照创建前命中已有活跃连接的次数，不会随快照对象后续变化。
    private final long reusedConnections;

    /**
     * 用提供者读取到的累计值创建快照，包内构造避免调用方伪造统计来源。
     */
    ConnectionStatistics(long createdConnections, long reusedConnections) {
        this.createdConnections = createdConnections;
        this.reusedConnections = reusedConnections;
    }

    public long getCreatedConnections() {
        return createdConnections;
    }

    public long getReusedConnections() {
        return reusedConnections;
    }
}
