package com.github.hgdcoder.config;

import com.github.hgdcoder.remoting.dto.RpcRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
