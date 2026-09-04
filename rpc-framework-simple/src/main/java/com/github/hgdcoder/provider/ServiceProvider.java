package com.github.hgdcoder.provider;

import com.github.hgdcoder.config.RpcServiceConfig;

import java.net.InetSocketAddress;

public interface ServiceProvider {
    /**
     * Adds the service object to the local service map.
     */
    void addService(RpcServiceConfig rpcServiceConfig);

    /**
     * Gets the real service object by rpcServiceName.
     */
    Object getService(String rpcServiceName);

    /**
     * Publishes the service. In V4 this means local add plus file registry
     * registration when a registry is configured.
     */
    void publishService(RpcServiceConfig rpcServiceConfig);

    /** Netty 绑定成功后，把全部本地服务发布到实际监听地址。 */
    void publishAllServices(InetSocketAddress serverAddress);

    /** Netty 停止监听后，下线该地址的全部服务。 */
    void unpublishAllServices(InetSocketAddress serverAddress);
}
