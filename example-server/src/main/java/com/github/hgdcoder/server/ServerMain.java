package com.github.hgdcoder.server;

import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.registry.zk.CuratorUtils;
import com.github.hgdcoder.registry.zk.ZkServiceRegistry;
import com.github.hgdcoder.transport.netty.server.NettyRpcServer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class ServerMain {
    private static final String SERVER_PORT_PROPERTY = "flower.rpc.server.port";

    public static void main(String[] args) {
        /*
         * 可选参数优先级等同 -D：传入 9999、10000 可启动多个提供者。
         * 不传参数时不写 System property，让 flower-rpc.properties 或框架默认值生效。
         */
        if (args.length > 0) {
            System.setProperty(SERVER_PORT_PROPERTY, args[0]);
        }

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(ServerConfiguration.class);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // 先关闭 Spring，使 NettyRpcServer 的销毁方法完成，再结束 Curator 会话和临时节点。
            try {
                context.close();
            } finally {
                CuratorUtils.closeZkClient();
            }
        }, "flower-rpc-server-shutdown"));

        try {
            context.refresh();
            context.getBean(NettyRpcServer.class).start();
        } catch (RuntimeException | Error e) {
            // 启动失败同样按正常依赖顺序回收已经创建的 Bean 与 ZooKeeper 资源。
            try {
                context.close();
            } finally {
                CuratorUtils.closeZkClient();
            }
            throw e;
        }
    }
}
