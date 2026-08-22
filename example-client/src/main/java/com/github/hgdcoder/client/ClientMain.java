package com.github.hgdcoder.client;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;
import com.github.hgdcoder.loadbalance.loadbalancer.ConsistentHashLoadBalance;
import com.github.hgdcoder.proxy.RpcClientProxy;
import com.github.hgdcoder.registry.zk.CuratorUtils;
import com.github.hgdcoder.registry.zk.ZkServiceDiscovery;
import com.github.hgdcoder.transport.netty.client.NettyRpcClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class ClientMain {
    public static void main(String[] args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try{
            context.register(ClientConfiguration.class);
            context.refresh();
            String result = context.getBean(HelloController.class).callHelloService();
            System.out.println(result);
        }finally{
            // context.close 先销毁 NettyRpcClient，再关闭 Curator 的监听器和共享会话。
            try{
                context.close();
            }finally{
                CuratorUtils.closeZkClient();
            }
        }
    }
}
