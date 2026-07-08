package com.github.hgdcoder.client;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;
import com.github.hgdcoder.proxy.RpcClientProxy;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.socket.SocketRpcClient;

import java.util.UUID;

public class ClientMain {
    public static void main(String[] args) {
        SocketRpcClient socketRpcClient  = new SocketRpcClient("127.0.0.1", 9998);

        RpcClientProxy rpcClientProxy = new RpcClientProxy(socketRpcClient,"test","1.0");

        HelloService helloService = rpcClientProxy.getProxy(HelloService.class);

        String result = helloService.hello(new Hello("Flower","RPC V2"));

        System.out.println(result);
    }
}
