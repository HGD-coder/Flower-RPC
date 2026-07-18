package com.github.hgdcoder.registry.zk;

import com.github.hgdcoder.registry.ServiceRegistry;
import org.apache.curator.framework.CuratorFramework;

import java.net.InetSocketAddress;

/**
 * 基于 ZooKeeper 的服务注册实现。
 *
 * 服务端发布服务时，会把“完整服务名 + 服务地址”写入 ZooKeeper。
 */
public class ZkServiceRegistry implements ServiceRegistry {
    @Override
    public void registerService(
            String rpcServiceName,
            InetSocketAddress inetSocketAddress
    ) {
        // 服务地址作为服务节点的名称，例如 127.0.0.1:9998。
        String serviceAddress = inetSocketAddress.getHostString()
                + ":"
                + inetSocketAddress.getPort();

        // 最终路径结构：/flower-rpc/完整服务名/服务地址。
        String servicePath = CuratorUtils.ZK_REGISTER_ROOT_PATH
                + "/"
                + rpcServiceName
                + "/"
                + serviceAddress;

        // 复用当前 JVM 的 Curator 客户端，并注册临时服务节点。
        CuratorFramework zkClient = CuratorUtils.getZkClient();
        CuratorUtils.createEphemeralNode(zkClient, servicePath);
    }
}
