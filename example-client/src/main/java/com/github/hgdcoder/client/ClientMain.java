package com.github.hgdcoder.client;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import com.github.hgdcoder.remoting.dto.RpcResponse;
import com.github.hgdcoder.transport.socket.SocketRpcClient;

import java.util.UUID;

public class ClientMain {
    public static void main(String[] args) {
        SocketRpcClient client = new SocketRpcClient("127.0.0.1", 9998);

        RpcRequest request = RpcRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .interfaceName(HelloService.class.getName())
                .methodName("hello")
                .parameters(new Object[]{new Hello("Flower","RPC V1")})
                .paramTypes(new Class<?>[]{Hello.class})
                .group("test")
                .version("1.0")
                .build();

        RpcResponse response = (RpcResponse<?>)client.sendRpcRequest(request);
        System.out.println(response);
    }
}
