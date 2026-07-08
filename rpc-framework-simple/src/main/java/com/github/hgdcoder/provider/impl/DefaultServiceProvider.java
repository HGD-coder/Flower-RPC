package com.github.hgdcoder.provider.impl;

import com.github.hgdcoder.provider.ServiceProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultServiceProvider implements ServiceProvider {
    private final Map<String,Object> serviceMap = new ConcurrentHashMap<>();

    @Override
    public void addService(Object service,String group,String version){
            Class<?>[] interfaces = service.getClass().getInterfaces();
            for(Class<?> clazz : interfaces){
                String serviceName = clazz.getName()+group+version;
                serviceMap.put(serviceName,service);
            }
    }

    @Override
    public Object getService(String serviceName) {
        Object service = serviceMap.get(serviceName);
        if(service == null){
            throw new RuntimeException("No service found for name:"+serviceName);
        }
        return service;
    }
}
