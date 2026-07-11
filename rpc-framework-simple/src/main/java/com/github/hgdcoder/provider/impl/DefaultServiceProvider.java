package com.github.hgdcoder.provider.impl;

import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.registry.ServiceRegistry;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultServiceProvider implements ServiceProvider {
    /**
     * key: rpcServiceName
     * value: the real service object, for example HelloServiceImpl
     */
    private final Map<String, Object> serviceMap = new ConcurrentHashMap<>();

    /**
     * Avoids publishing the same service repeatedly.
     */
    private final Set<String> registeredService = ConcurrentHashMap.newKeySet();

    private final ServiceRegistry serviceRegistry;
    private final InetSocketAddress serviceAddress;

    public DefaultServiceProvider() {
        this(null, null);
    }

    public DefaultServiceProvider(ServiceRegistry serviceRegistry, InetSocketAddress serviceAddress) {
        this.serviceRegistry = serviceRegistry;
        this.serviceAddress = serviceAddress;
    }

    @Override
    public void addService(RpcServiceConfig rpcServiceConfig) {
        String rpcServiceName = rpcServiceConfig.getRpcServiceName();

        if (!registeredService.add(rpcServiceName)) {
            return;
        }

        serviceMap.put(rpcServiceName, rpcServiceConfig.getService());
    }

    @Override
    public Object getService(String rpcServiceName) {
        Object service = serviceMap.get(rpcServiceName);

        if (service == null) {
            throw new RuntimeException("No service found for name: " + rpcServiceName);
        }

        return service;
    }

    @Override
    public void publishService(RpcServiceConfig rpcServiceConfig) {
        addService(rpcServiceConfig);

        if (serviceRegistry != null && serviceAddress != null) {
            serviceRegistry.registerService(rpcServiceConfig.getRpcServiceName(), serviceAddress);
        }
    }
}
