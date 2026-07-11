package com.github.hgdcoder.provider;

import com.github.hgdcoder.config.RpcServiceConfig;

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
}
