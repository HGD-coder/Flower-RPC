package com.github.hgdcoder.provider.impl;

import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.registry.ServiceRegistry;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DefaultServiceProviderLifecycleTest {
    @Test
    void shouldPublishAndUnpublishIdempotently() {
        RecordingRegistry registry = new RecordingRegistry();
        DefaultServiceProvider provider = new DefaultServiceProvider(registry, null);
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9998);

        // 加入本地容器，不应该立即写入注册中心。
        provider.addService(config(new FirstServiceImpl()));
        assertTrue(registry.registered.isEmpty());

        // 重复发布只应真正调用一次注册中心。
        provider.publishAllServices(address);
        provider.publishAllServices(address);
        assertEquals(1, registry.registered.size());
        assertEquals(1, registry.registerCalls);

        // 重复注销不会报错，最终注册中心为空。
        provider.unpublishAllServices(address);
        provider.unpublishAllServices(address);
        assertTrue(registry.registered.isEmpty());
    }

    @Test
    void shouldRollbackWhenOnePublicationFails() {
        RecordingRegistry registry = new RecordingRegistry();
        registry.failOnRegisterCall = 2; // 故意让第二次注册失败。
        DefaultServiceProvider provider = new DefaultServiceProvider(registry, null);
        provider.addService(config(new FirstServiceImpl()));
        provider.addService(config(new SecondServiceImpl()));

        assertThrows(IllegalStateException.class, () ->
                provider.publishAllServices(new InetSocketAddress("127.0.0.1", 9998)));
        // 第一个成功项应被回滚，不能留下部分发布结果。
        assertTrue(registry.registered.isEmpty());
    }

    private RpcServiceConfig config(Object service) {
        return RpcServiceConfig.builder()
                .service(service).group("test").version("1.0").build();
    }

    private interface FirstService {}
    private interface SecondService {}
    private static final class FirstServiceImpl implements FirstService {}
    private static final class SecondServiceImpl implements SecondService {}

    /** 用集合模拟远程注册表，测试时不访问网络。 */
    private static final class RecordingRegistry implements ServiceRegistry {
        private final Set<String> registered = new HashSet<>();
        private int registerCalls;
        private int failOnRegisterCall = -1;

        @Override
        public void registerService(String name, InetSocketAddress address) {
            if (++registerCalls == failOnRegisterCall) {
                throw new IllegalStateException("deliberate publication failure");
            }
            registered.add(name + "@" + address);
        }

        @Override
        public void unregisterService(String name, InetSocketAddress address) {
            registered.remove(name + "@" + address);
        }
    }
}