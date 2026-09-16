package com.github.hgdcoder.provider;

import com.github.hgdcoder.annotation.RpcSlow;
import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.remoting.dto.RpcRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcMethodExecutionRegistryTest {

    @Test
    void shouldReadImplementationAnnotationAndDefaultOtherMethodsToFast() {
        RpcMethodExecutionRegistry registry =
                new RpcMethodExecutionRegistry();
        registry.register(config(
                ImplementationAnnotatedService.class,
                new ImplementationAnnotatedServiceImpl(),
                "manual",
                "1.0"
        ));

        assertTrue(registry.isSlow(request(
                ImplementationAnnotatedService.class,
                "manual",
                "1.0",
                "slow",
                String.class
        )));
        assertFalse(registry.isSlow(request(
                ImplementationAnnotatedService.class,
                "manual",
                "1.0",
                "fast"
        )));
    }

    @Test
    void shouldReadAnnotationDeclaredOnServiceInterface() {
        RpcMethodExecutionRegistry registry =
                new RpcMethodExecutionRegistry();
        registry.register(config(
                InterfaceAnnotatedService.class,
                new InterfaceAnnotatedServiceImpl(),
                "interface",
                "1.0"
        ));

        assertTrue(registry.isSlow(request(
                InterfaceAnnotatedService.class,
                "interface",
                "1.0",
                "slowFromContract"
        )));
    }

    @Test
    void shouldClassifyOverloadedMethodsByParameterTypes() {
        RpcMethodExecutionRegistry registry =
                new RpcMethodExecutionRegistry();
        registry.register(config(
                OverloadedService.class,
                new OverloadedServiceImpl(),
                "overload",
                "1.0"
        ));

        assertTrue(registry.isSlow(request(
                OverloadedService.class,
                "overload",
                "1.0",
                "execute",
                String.class
        )));
        assertFalse(registry.isSlow(request(
                OverloadedService.class,
                "overload",
                "1.0",
                "execute",
                Integer.class
        )));
    }

    @Test
    void shouldKeepGroupAndVersionClassificationsIndependent() {
        RpcMethodExecutionRegistry registry =
                new RpcMethodExecutionRegistry();
        registry.register(config(
                GroupedService.class,
                new SlowGroupedService(),
                "slow-group",
                "1.0"
        ));
        registry.register(config(
                GroupedService.class,
                new FastGroupedService(),
                "fast-group",
                "2.0"
        ));

        assertTrue(registry.isSlow(request(
                GroupedService.class,
                "slow-group",
                "1.0",
                "work"
        )));
        assertFalse(registry.isSlow(request(
                GroupedService.class,
                "fast-group",
                "2.0",
                "work"
        )));
        assertFalse(registry.isSlow(request(
                GroupedService.class,
                "slow-group",
                "2.0",
                "work"
        )));
        assertFalse(registry.isSlow(request(
                GroupedService.class,
                "fast-group",
                "1.0",
                "work"
        )));
    }

    @Test
    void shouldIgnoreStaticInterfaceHelperMethodsDuringRegistration() {
        RpcMethodExecutionRegistry registry =
                new RpcMethodExecutionRegistry();

        assertDoesNotThrow(() -> registry.register(config(
                StaticHelperService.class,
                new StaticHelperServiceImpl(),
                "static-helper",
                "1.0"
        )));
        assertFalse(registry.isSlow(request(
                StaticHelperService.class,
                "static-helper",
                "1.0",
                "fast"
        )));
    }

    private static RpcServiceConfig config(
            Class<?> serviceInterface,
            Object service,
            String group,
            String version
    ) {
        return RpcServiceConfig.builder()
                .serviceName(serviceInterface.getName())
                .serviceClass(service.getClass())
                .service(service)
                .group(group)
                .version(version)
                .build();
    }

    private static RpcRequest request(
            Class<?> serviceInterface,
            String group,
            String version,
            String methodName,
            Class<?>... parameterTypes
    ) {
        return RpcRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .interfaceName(serviceInterface.getName())
                .methodName(methodName)
                .parameters(new Object[parameterTypes.length])
                .paramTypes(parameterTypes)
                .group(group)
                .version(version)
                .build();
    }

    private interface ImplementationAnnotatedService {
        String slow(String value);

        String fast();
    }

    private static final class ImplementationAnnotatedServiceImpl
            implements ImplementationAnnotatedService {
        @RpcSlow
        @Override
        public String slow(String value) {
            return value;
        }

        @Override
        public String fast() {
            return "fast";
        }
    }

    private interface InterfaceAnnotatedService {
        @RpcSlow
        String slowFromContract();
    }

    private static final class InterfaceAnnotatedServiceImpl
            implements InterfaceAnnotatedService {
        @Override
        public String slowFromContract() {
            return "slow";
        }
    }

    private interface OverloadedService {
        String execute(String value);

        String execute(Integer value);
    }

    private static final class OverloadedServiceImpl
            implements OverloadedService {
        @RpcSlow
        @Override
        public String execute(String value) {
            return value;
        }

        @Override
        public String execute(Integer value) {
            return String.valueOf(value);
        }
    }

    private interface GroupedService {
        String work();
    }

    private static final class SlowGroupedService
            implements GroupedService {
        @RpcSlow
        @Override
        public String work() {
            return "slow";
        }
    }

    private static final class FastGroupedService
            implements GroupedService {
        @Override
        public String work() {
            return "fast";
        }
    }

    private interface StaticHelperService {
        static String helper() {
            return "helper";
        }

        String fast();
    }

    private static final class StaticHelperServiceImpl
            implements StaticHelperService {
        @Override
        public String fast() {
            return "fast";
        }
    }
}
