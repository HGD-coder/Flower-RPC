package com.github.hgdcoder.extension;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.registry.ServiceRegistry;
import com.github.hgdcoder.transport.RpcRequestTransport;

/**
 * 统一创建需要配置参数或具有生命周期的 RPC 扩展。
 *
 * <p>它位于 ExtensionLoader 和具体业务装配之间：</p>
 *
 * <pre>
 * Spring 配置
 *     ↓
 * RpcExtensionFactory
 *     ↓
 * ExtensionLoader
 *     ↓
 * SPI 文件
 *     ↓
 * 具体实现类
 * </pre>
 *
 * <p>这里全部使用 newExtension()，每次都会创建新对象。
 * 关闭某个客户端后，不会影响下一次创建的客户端。</p>
 */
public final class RpcExtensionFactory {

    /**
     * 工具类不允许通过 new RpcExtensionFactory() 创建对象。
     */
    private RpcExtensionFactory() {
    }

    /**
     * 根据配置创建服务注册实现。
     *
     * <p>例如：</p>
     * <pre>
     * registry=zk   → ZkServiceRegistry
     * registry=file → FileServiceRegistry
     * </pre>
     */
    public static ServiceRegistry createServiceRegistry(
            RpcFrameworkConfig config
    ) {
        requireConfig(config);

        return ExtensionLoader
                .getExtensionLoader(ServiceRegistry.class)
                .newExtension(
                        config.getRegistry(),

                        /*
                         * 指定实现类必须提供：
                         *
                         * public XxxServiceRegistry(
                         *     RpcFrameworkConfig config
                         * )
                         */
                        new Class<?>[]{
                                RpcFrameworkConfig.class
                        },

                        /*
                         * 调用构造器时实际传入的参数。
                         */
                        config
                );
    }

    /**
     * 根据配置创建服务发现实现。
     *
     * <p>例如：</p>
     * <pre>
     * discovery=zk   → ZkServiceDiscovery
     * discovery=file → FileServiceDiscovery
     * </pre>
     */
    public static ServiceDiscovery createServiceDiscovery(
            RpcFrameworkConfig config
    ) {
        requireConfig(config);

        return ExtensionLoader
                .getExtensionLoader(ServiceDiscovery.class)
                .newExtension(
                        config.getDiscovery(),

                        /*
                         * 所有服务发现实现统一提供：
                         *
                         * public XxxServiceDiscovery(
                         *     RpcFrameworkConfig config
                         * )
                         */
                        new Class<?>[]{
                                RpcFrameworkConfig.class
                        },

                        config
                );
    }

    /**
     * 根据配置创建客户端请求传输实现。
     *
     * <p>例如：</p>
     * <pre>
     * transport=netty → NettyRpcClient
     * transport=socket → SocketRpcClient
     * </pre>
     */
    public static RpcRequestTransport
    createRpcRequestTransport(
            ServiceDiscovery serviceDiscovery,
            RpcFrameworkConfig config
    ) {
        if (serviceDiscovery == null) {
            throw new IllegalArgumentException(
                    "serviceDiscovery must not be null"
            );
        }

        requireConfig(config);

        return ExtensionLoader
                .getExtensionLoader(
                        RpcRequestTransport.class
                )
                .newExtension(
                        config.getTransport(),

                        /*
                         * 所有客户端传输实现统一提供：
                         *
                         * public XxxRpcClient(
                         *     ServiceDiscovery serviceDiscovery,
                         *     RpcFrameworkConfig config
                         * )
                         */
                        new Class<?>[]{
                                ServiceDiscovery.class,
                                RpcFrameworkConfig.class
                        },

                        serviceDiscovery,
                        config
                );
    }

    /**
     * 配置是整个扩展装配过程的基础，不能为 null。
     */
    private static void requireConfig(
            RpcFrameworkConfig config
    ) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null"
            );
        }
    }
}