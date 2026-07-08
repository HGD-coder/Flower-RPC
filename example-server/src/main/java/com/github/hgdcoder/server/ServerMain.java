package com.github.hgdcoder.server;

import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.transport.socket.SocketRpcServer;

public class ServerMain {
    public static void main(String[] args) {
        DefaultServiceProvider serviceProvider = new DefaultServiceProvider();
        serviceProvider.addService(new HelloServiceImpl(),"test","1.0");

        SocketRpcServer server = new SocketRpcServer(9998,serviceProvider);
        server.start();
    }
}
