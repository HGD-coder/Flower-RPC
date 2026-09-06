package com.github.hgdcoder.client;

import com.github.hgdcoder.annotation.RpcScan;
import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.extension.RpcExtensionFactory;
import com.github.hgdcoder.loadbalance.loadbalancer.ConsistentHashLoadBalance;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.registry.zk.ZkServiceDiscovery;
import com.github.hgdcoder.transport.RpcRequestTransport;
import com.github.hgdcoder.transport.netty.client.NettyRpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 客户端 Spring 装配入口。
 *
 * <p>负责创建统一配置、服务发现和客户端传输对象。</p>
 */
@Configuration
@RpcScan(basePackage = "com.github.hgdcoder.client")
public class ClientConfiguration {

    /**
     * 创建客户端共享的配置快照。
     *
     * <p>后面的服务发现和传输客户端都会获得同一个对象。</p>
     */
    @Bean
    public RpcFrameworkConfig rpcFrameworkConfig() {
        /*
         * 发布地址、监听地址、注册中心和心跳参数
         * 必须来自同一次配置加载。
         */
        return RpcFrameworkConfig.load();
    }

    /**
     * 根据 flower.rpc.discovery 创建服务发现实现。
     */
    @Bean
    public ServiceDiscovery serviceDiscovery(
            RpcFrameworkConfig config
    ) {
        return RpcExtensionFactory
                .createServiceDiscovery(config);
    }

    /**
     * 根据 flower.rpc.transport 创建客户端传输实现。
     *
     * <p>Spring 关闭容器时会调用具体实现的 close()，
     * 释放连接和线程资源。</p>
     */
    @Bean(destroyMethod = "close")
    public RpcRequestTransport rpcRequestTransport(
            ServiceDiscovery serviceDiscovery,
            RpcFrameworkConfig config
    ){
        return RpcExtensionFactory
                .createRpcRequestTransport(
                        serviceDiscovery,
                        config
                );
    }
}
