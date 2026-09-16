package com.github.hgdcoder.spring;

import com.github.hgdcoder.annotation.RpcService;
import com.github.hgdcoder.annotation.RpcSlow;
import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.provider.impl.DefaultServiceProvider;
import com.github.hgdcoder.remoting.dto.RpcRequest;
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

    @Test
    void shouldRegisterSlowMethodFromUserClassWhenFinalBeanIsProxy() {
        DefaultServiceProvider provider = new DefaultServiceProvider();
        SpringBeanPostProcessor processor = processor(provider);
        EchoService$$SpringCglib proxy =
                new EchoService$$SpringCglib();

        processor.postProcessBeforeInitialization(
                proxy,
                "echoService"
        );
        processor.postProcessAfterInitialization(
                proxy,
                "echoService"
        );

        RpcRequest request = RpcRequest.builder()
                .requestId("spring-slow-request")
                .interfaceName(EchoService.class.getName())
                .methodName("slow")
                .parameters(new Object[0])
                .paramTypes(new Class<?>[0])
                .group("")
                .version("")
                .build();

        assertTrue(
                provider.isSlowRequest(request),
                "Spring 代理后的服务没有保留原始类上的 @RpcSlow"
        );
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
        String slow();
    }

    @RpcService
    private static class EchoServiceImpl implements EchoService {
        @RpcSlow
        @Override
        public String slow() {
            return "slow";
        }
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
