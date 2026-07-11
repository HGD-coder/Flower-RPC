package com.github.hgdcoder.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class RpcServiceConfig {
    /**
     * Service version. Different versions of the same interface can coexist.
     */
    @Builder.Default
    private String version = "";

    /**
     * Service group. Different implementations of the same interface can be
     * separated by group.
     */
    @Builder.Default
    private String group = "";

    /**
     * The actual service object, for example new HelloServiceImpl().
     */
    private Object service;

    /**
     * Full service key used by both client and server:
     * interface name + group + version.
     */
    public String getRpcServiceName() {
        return getServiceName() + group + version;
    }

    /**
     * V4 keeps the simple convention that a service implementation exposes its
     * first interface as the RPC service interface.
     */
    public String getServiceName() {
        Class<?>[] interfaces = service.getClass().getInterfaces();
        if (interfaces.length == 0) {
            throw new IllegalArgumentException("Rpc service must implement an interface");
        }
        return interfaces[0].getName();
    }
}
