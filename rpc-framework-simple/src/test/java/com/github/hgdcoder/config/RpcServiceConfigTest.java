package com.github.hgdcoder.config;

import com.github.hgdcoder.remoting.dto.RpcRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RpcServiceConfigTest {

    @Test
    void shouldBuildTheSameServiceKeyAsRpcRequestWithExplicitServiceName() {
        String serviceName = "com.example.EchoService";
        String group = "\u7814\u53d1";
        String version = "v1.0";
        RpcRequest request = RpcRequest.builder()
                .interfaceName(serviceName)
                .group(group)
                .version(version)
                .build();
        RpcServiceConfig serviceConfig = RpcServiceConfig.builder()
                .serviceName(serviceName)
                .group(group)
                .version(version)
                .service(new Object())
                .build();

        assertEquals(serviceName, serviceConfig.getServiceName());
        assertEquals(request.getRpcServiceName(), serviceConfig.getRpcServiceName());
    }

    @Test
    void shouldKeepFourArgumentConstructorCompatible() {
        Object service = new Object();
        RpcServiceConfig serviceConfig = new RpcServiceConfig(
                "com.example.EchoService",
                "v1",
                "default",
                service
        );

        assertEquals("com.example.EchoService", serviceConfig.getServiceName());
        assertEquals("v1", serviceConfig.getVersion());
        assertEquals("default", serviceConfig.getGroup());
        assertSame(service, serviceConfig.getService());
        assertSame(service.getClass(), serviceConfig.getServiceClass());
    }
}
