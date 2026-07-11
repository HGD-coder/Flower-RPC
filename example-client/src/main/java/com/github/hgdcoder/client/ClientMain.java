package com.github.hgdcoder.client;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;
import com.github.hgdcoder.proxy.RpcClientProxy;
import com.github.hgdcoder.registry.file.FileServiceDiscovery;
import com.github.hgdcoder.transport.socket.SocketRpcClient;

public class ClientMain {
    public static void main(String[] args) {
        SocketRpcClient socketRpcClient = new SocketRpcClient(new FileServiceDiscovery());
        RpcClientProxy rpcClientProxy = new RpcClientProxy(socketRpcClient, "test", "1.0");
        HelloService helloService = rpcClientProxy.getProxy(HelloService.class);

        String result = helloService.hello(new Hello("Flower", "RPC V4"));

        System.out.println(result);
    }
}
