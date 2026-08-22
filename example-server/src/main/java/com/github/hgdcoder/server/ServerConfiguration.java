package com.github.hgdcoder.server;

import com.github.hgdcoder.annotation.RpcScan;
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
    public ZkServiceRegistry zkServiceRegistry() {
        return new ZkServiceRegistry();
    }

    @Bean
    public DefaultServiceProvider serviceProvider(
            ZkServiceRegistry serviceRegistry,
            @Value("${flower.rpc.server.port:9998}") int port) {
            return new DefaultServiceProvider(
                    serviceRegistry,
                    new InetSocketAddress("127.0.0.1",port)
            );
    }

    @Bean(destroyMethod = "close")
    public NettyRpcServer nettyRpcServer(
            DefaultServiceProvider serviceProvider,
            @Value("${flower.rpc.server.port:9998}") int port
    ){
        // Spring 关闭上下文时调用 close，统一回收监听 Channel 和三组 Netty 线程。
        return new NettyRpcServer(port, serviceProvider);
    }
}
