package com.github.hgdcoder.client;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;
import com.github.hgdcoder.loadbalance.loadbalancer.ConsistentHashLoadBalance;
import com.github.hgdcoder.proxy.RpcClientProxy;
import com.github.hgdcoder.registry.zk.CuratorUtils;
import com.github.hgdcoder.registry.zk.ZkServiceDiscovery;
import com.github.hgdcoder.transport.socket.SocketRpcClient;

public class ClientMain {
    public static void main(String[] args) {
        SocketRpcClient socketRpcClient = new SocketRpcClient(
                new ZkServiceDiscovery(new ConsistentHashLoadBalance())
        );
        RpcClientProxy rpcClientProxy = new RpcClientProxy(socketRpcClient, "test", "1.0");
        HelloService helloService = rpcClientProxy.getProxy(HelloService.class);

        try {
            String result = helloService.hello(new Hello("Flower", "RPC V7"));
            System.out.println(result);
        } finally {
            // 示例程序结束前同时释放 RPC 连接和 ZooKeeper 后台线程，
            // 否则在 IDEA 中可能看到 main 方法执行完毕但进程没有立即退出。
            socketRpcClient.closeCurrentThreadConnections();
            CuratorUtils.closeZkClient();
        }
    }
}
