package com.github.hgdcoder.server;

import com.github.hgdcoder.annotation.RpcScan;
import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.registry.zk.ZkServiceRegistry;
import com.github.hgdcoder.transport.netty.server.NettyRpcServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

/**
 * 服务端 Spring 装配入口：扫描 RPC 服务，并声明注册中心、服务提供者和 Netty 服务端。
 */
@Configuration
@RpcScan(basePackage="com.github.hgdcoder.server")
public class ServerConfiguration {
    @Bean
    public RpcFrameworkConfig rpcFrameworkConfig() {
        // 注册地址、监听地址与心跳参数必须来自同一次加载，避免组件之间观察到不同值。
        return RpcFrameworkConfig.load();
    }

    @Bean
    public ZkServiceRegistry zkServiceRegistry(RpcFrameworkConfig config) {
        return new ZkServiceRegistry(config);
    }

    @Bean
    public DefaultServiceProvider serviceProvider(
            ZkServiceRegistry serviceRegistry,
            RpcFrameworkConfig config) {
            return new DefaultServiceProvider(
                    serviceRegistry,
                    new InetSocketAddress(
                            config.getServerHost(),
                            config.getServerPort()
                    )
            );
    }

    @Bean(destroyMethod = "close")
    public NettyRpcServer nettyRpcServer(
            DefaultServiceProvider serviceProvider,
            RpcFrameworkConfig config
    ){
        // Spring 关闭上下文时调用 close，统一回收监听 Channel 和三组 Netty 线程。
        return new NettyRpcServer(config, serviceProvider);
    }
}
