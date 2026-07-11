package com.github.hgdcoder.server;

import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.registry.file.FileServiceRegistry;
import com.github.hgdcoder.transport.socket.SocketRpcServer;

import java.net.InetSocketAddress;

public class ServerMain {
    public static void main(String[] args) {
        int port = 9998;

        DefaultServiceProvider serviceProvider = new DefaultServiceProvider(
                new FileServiceRegistry(),
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
