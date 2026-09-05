package com.github.hgdcoder.server;

import com.github.hgdcoder.annotation.RpcScan;
import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.extension.RpcExtensionFactory;
import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.registry.ServiceRegistry;
import com.github.hgdcoder.registry.zk.ZkServiceRegistry;
import com.github.hgdcoder.transport.netty.server.NettyRpcServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

/**
 * 服务端 Spring 装配入口。
 *
 * <p>负责创建配置、服务注册器、服务提供者和 Netty 服务端。</p>
 */
@Configuration
@RpcScan(basePackage="com.github.hgdcoder.server")
public class ServerConfiguration {

    /**
     * 创建整个服务端共享的配置快照。
     */
    @Bean
    public RpcFrameworkConfig rpcFrameworkConfig() {
        // 注册地址、监听地址与心跳参数必须来自同一次加载，避免组件之间观察到不同值。
        return RpcFrameworkConfig.load();
    }

    /**
     * 根据 flower.rpc.registry 创建服务注册实现。
     */
    @Bean
    public ServiceRegistry serviceRegistry(
            RpcFrameworkConfig config
    ) {
        return RpcExtensionFactory.createServiceRegistry(config);
    }

    /**
     * 创建服务提供者。
     *
     * <p>DefaultServiceProvider 负责保存服务实例，
     * 并通过 ServiceRegistry 发布服务地址。</p>
     */
    @Bean
    public DefaultServiceProvider serviceProvider(
            ServiceRegistry serviceRegistry,
            RpcFrameworkConfig config
    ) {
            return new DefaultServiceProvider(
                    serviceRegistry,
                    new InetSocketAddress(
                            config.getServerHost(),
                            config.getServerPort()
                    )
            );
    }

    /**
     * 创建 Netty RPC 服务端。
     *
     * <p>这里暂时仍然显式使用 NettyRpcServer。
     * V21 的 transport SPI 指的是客户端请求传输，
     * 不是服务端监听器。</p>
     */
    @Bean(destroyMethod = "close")
    public NettyRpcServer nettyRpcServer(
            DefaultServiceProvider serviceProvider,
            RpcFrameworkConfig config
    ){
        // Spring 关闭上下文时调用 close，统一回收监听 Channel 和三组 Netty 线程。
        return new NettyRpcServer(config, serviceProvider);
    }
}
