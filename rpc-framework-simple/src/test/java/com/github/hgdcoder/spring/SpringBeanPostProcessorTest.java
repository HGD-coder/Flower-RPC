package com.github.hgdcoder.spring;

import com.github.hgdcoder.annotation.RpcService;
import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.transport.RpcRequestTransport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Spring 服务元数据不受初始化后代理类型影响。 */
class SpringBeanPostProcessorTest {

    @Test
    void shouldSaveTheUserClassInterfaceNameForTheFinalBean() {
        RecordingServiceProvider provider = new RecordingServiceProvider();
        SpringBeanPostProcessor processor = processor(provider);
        Object finalBean = new Object();

        /*
         * 类名中包含 $$，模拟 Spring CGLIB 代理。
         * ClassUtils.getUserClass() 应当回到它的父类 EchoServiceImpl。
         */
        processor.postProcessBeforeInitialization(
                new EchoService$$SpringCglib(),
                "echoService"
        );
        processor.postProcessAfterInitialization(
                finalBean,
                "echoService"
        );

        assertEquals(
                EchoService.class.getName(),
                provider.serviceConfig.getServiceName()
        );
        assertSame(
                finalBean,
                provider.serviceConfig.getService()
        );
    }

    @Test
    void shouldFailBeforeInitializationWhenRpcServiceHasNoInterface() {
        SpringBeanPostProcessor processor =
                processor(new RecordingServiceProvider());

        BeanCreationException error = assertThrows(
                BeanCreationException.class,
                () -> processor.postProcessBeforeInitialization(
                        new InterfaceLessService(),
                        "interfaceLessService"
                )
        );

        assertEquals("interfaceLessService", error.getBeanName());
        assertTrue(error.getMessage().contains("must implement an interface"));
    }

    private SpringBeanPostProcessor processor(
            ServiceProvider serviceProvider
    ) {
        DefaultListableBeanFactory beanFactory =
                new DefaultListableBeanFactory();
        beanFactory.registerSingleton(
                "serviceProvider",
                serviceProvider
        );

        ObjectProvider<ServiceProvider> providerLookup =
                beanFactory.getBeanProvider(ServiceProvider.class);
        ObjectProvider<RpcRequestTransport> transportLookup =
                beanFactory.getBeanProvider(RpcRequestTransport.class);

        return new SpringBeanPostProcessor(
                providerLookup,
                transportLookup
        );
    }

    private interface EchoService {
    }

    @RpcService
    private static class EchoServiceImpl implements EchoService {
    }

    private static class EchoService$$SpringCglib
            extends EchoServiceImpl {
    }

    @RpcService
    private static class InterfaceLessService {
    }

    /** 只记录 Spring 最终交给服务容器的配置，不访问网络或注册中心。 */
    private static final class RecordingServiceProvider
            implements ServiceProvider {

        private RpcServiceConfig serviceConfig;

        @Override
        public void addService(RpcServiceConfig rpcServiceConfig) {
            this.serviceConfig = rpcServiceConfig;
        }

        @Override
        public Object getService(String rpcServiceName) {
            return null;
        }

        @Override
        public void publishService(RpcServiceConfig rpcServiceConfig) {
        }

        @Override
        public void publishAllServices(InetSocketAddress serverAddress) {
        }

        @Override
        public void unpublishAllServices(InetSocketAddress serverAddress) {
        }
    }
}
