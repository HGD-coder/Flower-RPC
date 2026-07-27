package com.github.hgdcoder.server;

import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.registry.zk.CuratorUtils;
import com.github.hgdcoder.registry.zk.ZkServiceRegistry;
import com.github.hgdcoder.transport.netty.server.NettyRpcServer;

import java.net.InetSocketAddress;

public class ServerMain {
    public static void main(String[] args) {
        // 不传参数时使用 9998；可以分别传入 9999、10000 启动多个服务提供者。
        int port = args.length == 0 ? 9998 : Integer.parseInt(args[0]);

        DefaultServiceProvider serviceProvider = new DefaultServiceProvider(
                new ZkServiceRegistry(),
                new InetSocketAddress("127.0.0.1", port)
        );

        RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                .service(new HelloServiceImpl())
                .group("test")
                .version("1.0")
                .build();

        serviceProvider.publishService(rpcServiceConfig);

        NettyRpcServer server = new NettyRpcServer(port, serviceProvider);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // JVM 退出时先停止 Netty，再关闭 Curator，使服务节点随会话及时下线。
            try {
                server.close();
            } finally {
                CuratorUtils.closeZkClient();
            }
        }, "flower-rpc-server-shutdown"));
        server.start();
    }
}
