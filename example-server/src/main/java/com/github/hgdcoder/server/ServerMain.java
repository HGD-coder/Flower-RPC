package com.github.hgdcoder.server;

import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.registry.zk.ZkServiceRegistry;
import com.github.hgdcoder.transport.socket.SocketRpcServer;

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

        SocketRpcServer server = new SocketRpcServer(port, serviceProvider);
        server.start();
    }
}
