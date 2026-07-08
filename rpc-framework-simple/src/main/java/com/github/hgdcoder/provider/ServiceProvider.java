package com.github.hgdcoder.provider;

public interface ServiceProvider {
    void addService(Object service,String group,String version);

    Object getService(String rpcServiceName);
}
