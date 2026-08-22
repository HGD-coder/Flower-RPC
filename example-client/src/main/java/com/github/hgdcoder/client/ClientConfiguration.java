package com.github.hgdcoder.client;

import com.github.hgdcoder.annotation.RpcScan;
import com.github.hgdcoder.loadbalance.loadbalancer.ConsistentHashLoadBalance;
import com.github.hgdcoder.registry.zk.ZkServiceDiscovery;
import com.github.hgdcoder.transport.netty.client.NettyRpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 客户端 Spring 装配入口：扫描业务组件并提供唯一的 RPC 请求传输实现。
 */
@Configuration
@RpcScan(basePackage = "com.github.hgdcoder.client")
public class ClientConfiguration {
    @Bean(destroyMethod = "close")
    public NettyRpcClient nettyRpcClient(){
        // 服务发现继续使用 V11 的一致性哈希策略，Spring 只负责对象创建和关闭。
        return new NettyRpcClient(
                new ZkServiceDiscovery(new ConsistentHashLoadBalance())
        );
    }
}
