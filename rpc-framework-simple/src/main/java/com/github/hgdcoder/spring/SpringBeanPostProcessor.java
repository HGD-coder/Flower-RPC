package com.github.hgdcoder.spring;

import com.github.hgdcoder.annotation.RpcReference;
import com.github.hgdcoder.annotation.RpcService;
import com.github.hgdcoder.config.RpcServiceConfig;
import com.github.hgdcoder.provider.ServiceProvider;
import com.github.hgdcoder.proxy.RpcClientProxy;
import com.github.hgdcoder.transport.RpcRequestTransport;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接 Spring Bean 生命周期与 RPC 服务、引用元数据的后处理器。
 *
 * <p>{@link BeanPostProcessor} 是 Spring 针对每个 Bean 初始化过程提供的扩展点：
 * 容器会在初始化回调前后依次调用相应方法。本类在初始化前识别 RPC 注解、注入远程代理，
 * 并在初始化完成后使用 Spring 最终交回的 Bean 实例发布 RPC 服务。</p>
 *
 * <p>客户端方向：扫描 {@code @RpcReference} 字段 -> 创建 {@link RpcClientProxy} ->
 * 生成接口代理对象 -> 反射写入字段。服务端方向：记录 {@code @RpcService} 元数据 ->
 * 等 Bean 初始化完成 -> 调用 {@link ServiceProvider#addService(RpcServiceConfig)}。</p>
 */
public class SpringBeanPostProcessor implements BeanPostProcessor {
    /**
     * 延迟取得服务提供者，避免在后处理器自身创建时过早要求对应 Bean 已就绪。
     * 后续服务发布逻辑将通过它获取实际的 {@link ServiceProvider}。
     */
    private final ObjectProvider<ServiceProvider> serviceProviderProvider;
    /**
     * 延迟取得 RPC 请求传输实现，供后续创建 {@code @RpcReference} 代理时使用。
     */
    private final ObjectProvider<RpcRequestTransport> rpcRequestTransportProvider;

    /**
     * 按 Bean 名称缓存 {@code @RpcService} 的发布元数据。
     *
     * <p>并发 Map 与 Spring 单例创建可能交错的场景相容；计划中的流程是在初始化前记录服务配置，
     * 再在初始化后结合最终 Bean 实例发布。</p>
     */
    private final Map<String, RpcServiceMetadata> rpcServices = new ConcurrentHashMap<>();

    /**
     * 创建 Bean 后处理器，并接收后续 RPC 集成需要的两个延迟依赖。
     *
     * @param serviceProviderProvider 服务提供者的延迟访问入口
     * @param rpcRequestTransportProvider 请求传输层的延迟访问入口
     */
    public SpringBeanPostProcessor(
            ObjectProvider<ServiceProvider> serviceProviderProvider,
            ObjectProvider<RpcRequestTransport> rpcRequestTransportProvider) {
        this.serviceProviderProvider = serviceProviderProvider;
        this.rpcRequestTransportProvider = rpcRequestTransportProvider;
    }

    /**
     * Spring 在 Bean 的初始化方法（例如 {@code @PostConstruct}）执行前调用的回调。
     *
     * <p>这里先取出用户实际声明的类，避免 AOP 代理类影响注解查找；随后读取合并后的
     * {@link RpcService} 注解，以兼容注解层次结构和别名属性。方法目前没有完成后续处理，
     * 故意保持原状。</p>
     *
     * @param bean 正在初始化的 Bean 实例，可能已被 Spring 包装为代理  HelloServiceImpl@6a12
     * @param beanName Bean 在 Spring 容器中的名称  "helloServiceImpl"
     * @return 当前实现尚未完成，未返回处理结果
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName){
        // 取目标用户类，而不是可能由 AOP 创建的代理类，确保读取到业务类上的 RPC 注解。
        Class<?> beanClass = ClassUtils.getUserClass(bean);
        // 使用 Spring 的合并注解查找，支持元注解、组合注解以及 @AliasFor 等注解模型。
        RpcService rpcService = AnnotatedElementUtils.findMergedAnnotation(beanClass, RpcService.class);
        if(rpcService != null) {
            /*
             * 在 Bean 可能被其他后处理器包装之前，
             * 从用户编写的业务类中提取 RPC 服务接口。
             */
            Class<?>[] serviceInterfaces =
                    ClassUtils.getAllInterfacesForClass(beanClass);

            /*
             * RPC 客户端依赖接口生成动态代理。
             * 服务类没有实现接口时，直接终止 Spring 启动。
             */
            if (serviceInterfaces.length == 0) {
                throw serviceFailure(
                        beanName,
                        beanClass.getName(),
                        "@RpcService class must implement an interface",
                        null
                );
            }


            // 此时只记录发布参数，不立刻发布。初始化方法执行失败的 Bean 不应该暴露给远程客户端。
            rpcServices.put(
                    beanName,
                    new RpcServiceMetadata(
                            beanClass,

                            // 目前仍约定第一个接口是 RPC 服务接口。
                            serviceInterfaces[0].getName(),

                            rpcService.group(),
                            rpcService.version()
                    )
            );
        }

        // 在 @PostConstruct 等初始化回调之前注入代理，使初始化方法中也可以调用远程服务。
        injectRpcReferences(bean,beanName,beanClass);
        // BeanPostProcessor 必须把 Bean 交还给 Spring；返回 null 会中断后续处理器链。
        return bean;
    }

    /**
     *  Spring 在 Bean 完成初始化后调用。
     *  若该 Bean 是 RPC 服务，则将它加入本地服务容器。
     *  对注册中心的正式发布，由 Netty 启动成功后执行。
     *
     * @param bean 已完成初始化的 Bean；它可能是其他后处理器生成的最终代理对象
     * @param beanName Bean 在容器中的名称，也是查找暂存服务元数据的键
     * @return 继续交还给 Spring 的 Bean
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName){
        // remove 表示服务元数据只消费一次，同时避免缓存长期持有已经处理完的 Bean 信息。
        RpcServiceMetadata metadata = rpcServices.remove(beanName);
        if (metadata == null) {
            return bean;
        }

        ServiceProvider serviceProvider;
        try {
            // 到真正需要发布时才从 ObjectProvider 获取依赖，降低 Bean 创建顺序导致的循环依赖风险。
            serviceProvider = serviceProviderProvider.getIfAvailable();
        } catch (RuntimeException e) {
            throw serviceFailure(beanName, metadata.beanClass.getName(),
                    "cannot resolve ServiceProvider", e);
        }
        if (serviceProvider == null) {
            throw serviceFailure(beanName, metadata.beanClass.getName(),
                    "no ServiceProvider bean is available", null);
        }

        // 把 Spring 管理的实例和注解里的 group/version 转换成框架原有的服务配置对象。
        RpcServiceConfig serviceConfig = RpcServiceConfig.builder()
                /*
                 * 保存 Spring 最终交回的 Bean。
                 * 它有可能是经过 AOP 包装的代理对象。
                 */
                .service(bean)
                /**
                 * UserServiceImpl.class
                 * 原始业务类
                 */
                .serviceClass(metadata.beanClass)
                /*
                com.xxx.UserService
                 * 接口名来自初始化前的用户类，
                 * 不再依赖代理对象运行时暴露的接口顺序。
                 */
                .serviceName(metadata.serviceName)
                .group(metadata.group)
                .version(metadata.version)
                .build();
        try {
            /*
             * Bean 初始化成功，只代表业务对象准备好了，
             * 不代表 Netty 已经成功绑定端口。
             *
             * 此时只加入本地服务容器，不访问注册中心。
             * 等 Netty 绑定成功后，再统一发布这些服务。
             */
            serviceProvider.addService(serviceConfig);
        } catch (RuntimeException e) {
            // 本地登记失败时，让 Spring 启动失败，并保留原因
            throw serviceFailure(
                    beanName,
                    metadata.beanClass.getName(),
                    "failed to add local RPC service",
                    e
            );
        }
        return bean;
    }

    /**
     * 找出当前 Bean 的所有 RPC 引用字段，并为每个字段创建、注入接口代理。
     * 代理被调用时会继续进入 RpcClientProxy -> RpcRequestTransport 的原有客户端调用链。
     */
    private void injectRpcReferences(Object bean, String beanName, Class<?> beanClass) {
        List<ReferenceField> referenceFields = findReferenceFields(beanClass, beanName);
        if (referenceFields.isEmpty()) {
            return;
        }

        RpcRequestTransport transport;
        try {
            transport = rpcRequestTransportProvider.getIfAvailable();
        } catch (RuntimeException e) {
            Field field = referenceFields.get(0).field;
            throw referenceFailure(beanName, field,
                    "cannot resolve RpcRequestTransport", e);
        }
        if (transport == null) {
            Field field = referenceFields.get(0).field;
            throw referenceFailure(beanName, field,
                    "no RpcRequestTransport bean is available", null);
        }

        for (ReferenceField referenceField : referenceFields) {
            RpcReference reference = referenceField.reference;
            // 字段类型就是远程服务接口；group/version 决定最终请求匹配哪一个服务。
            Object proxy = new RpcClientProxy(
                    transport,
                    reference.group(),
                    reference.version()
            ).getProxy(referenceField.field.getType());
            setField(bean, beanName, referenceField.field, proxy);
        }
    }

    /**
     * 从当前类一直向父类查找字段，保证父类中声明的 {@code @RpcReference} 也能被处理。
     */
    private List<ReferenceField> findReferenceFields(Class<?> beanClass, String beanName) {
        List<ReferenceField> fields = new ArrayList<>();
        // 每轮扫描一层 declaredFields，然后沿继承链上移；Object 本身无需扫描。
        for (Class<?> current = beanClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                RpcReference reference = field.getAnnotation(RpcReference.class);
                if (reference == null) {
                    continue;
                }
                validateReferenceField(beanName, field);
                fields.add(new ReferenceField(field, reference));
            }
        }
        return fields;
    }

    /** 校验字段是否具备通过反射写入 RPC 代理的条件。 */
    private void validateReferenceField(String beanName, Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers)) {
            throw referenceFailure(beanName, field,
                    "@RpcReference field must not be static", null);
        }
        if (Modifier.isFinal(modifiers)) {
            throw referenceFailure(beanName, field,
                    "@RpcReference field must not be final", null);
        }
        if (!field.getType().isInterface()) {
            throw referenceFailure(beanName, field,
                    "@RpcReference field type must be an interface", null);
        }
    }

    @SuppressWarnings("deprecation")
    private void setField(Object bean, String beanName, Field field, Object value) {
        // Java 8 使用 isAccessible 记录调用前状态；无论注入成功与否都恢复，避免污染共享 Field 元数据。
        boolean accessible = field.isAccessible();
        try {
            if (!accessible) {
                field.setAccessible(true);
            }
            field.set(bean, value);
        } catch (IllegalAccessException | RuntimeException e) {
            throw referenceFailure(beanName, field,
                    "failed to inject RPC proxy", e);
        } finally {
            try {
                if (field.isAccessible() != accessible) {
                    field.setAccessible(accessible);
                }
            } catch (RuntimeException e) {
                throw referenceFailure(beanName, field,
                        "failed to restore field accessibility", e);
            }
        }
    }

    /** 将引用注入阶段的上下文统一包装为 Spring 能识别的 Bean 创建异常。 */
    private BeanCreationException referenceFailure(
            String beanName,
            Field field,
            String reason,
            Throwable cause
    ) {
        String description = "RPC reference processing failed for bean '"
                + beanName
                + "', field '"
                + field.getDeclaringClass().getName()
                + "#"
                + field.getName()
                + "': "
                + reason;
        return cause == null
                ? new BeanCreationException(beanName, description)
                : new BeanCreationException(beanName, description, cause);
    }

    /** 将服务发布阶段的失败统一包装为 Spring Bean 创建异常，使容器启动立即失败并保留原因链。 */
    private BeanCreationException serviceFailure(
            String beanName,
            String beanClassName,
            String reason,
            Throwable cause
    ) {
        String description = "RPC service processing failed for bean '"
                + beanName
                + "' ("
                + beanClassName
                + "): "
                + reason;
        return cause == null
                ? new BeanCreationException(beanName, description)
                : new BeanCreationException(beanName, description, cause);
    }


    /**
     * 描述一个待处理的 {@code @RpcReference} 字段及其已解析的注解配置。
     * 后续引用注入逻辑可避免重复反射读取同一字段的注解。
     */
    // static 表示该元数据对象不依赖外部 SpringBeanPostProcessor 实例，避免隐式持有外部对象。
    // final 表示它只是内部固定的数据载体，不允许被继承改变语义。
    private static final class ReferenceField {
        /** 需要写入 RPC 代理对象的反射字段。 */
        private final Field field;
        /** 字段上的 RPC 引用配置，例如目标服务的分组和版本。 */
        private final RpcReference reference;

        /**
         * 组合保存反射字段与对应的注解元数据。
         *
         * @param field 标注了 {@code @RpcReference} 的字段
         * @param reference 从该字段读取到的 RPC 引用配置
         */
        private ReferenceField(Field field, RpcReference reference) {
            this.field = field;
            this.reference = reference;
        }
    }

    /**
     * 描述一个 @RpcService Bean 的发布元数据。
     *
     * 这些信息在 Bean 初始化前保存，
     * 在 Bean 初始化成功后使用。
     */
    private static final class RpcServiceMetadata {
        /**
         * Spring 创建代理前的用户业务类。
         */
        private final Class<?> beanClass;

        /**
         * RPC 服务接口的全限定名。
         *
         * 例如：
         * com.github.hgdcoder.HelloService
         */
        private final String serviceName;

        /** 服务分组，用于隔离同一服务的不同逻辑分区。 */
        private final String group;

        /** 服务版本，用于区分兼容演进中的不同服务实现。 */
        private final String version;

        /**
         * 创建服务发布元数据。
         *
         * @param beanClass
         * @param serviceName
         * @param group 服务分组
         * @param version 服务版本
         */
        private RpcServiceMetadata(
                Class<?> beanClass,
                String serviceName,
                String group,
                String version
        ) {
            this.beanClass = beanClass;
            this.serviceName = serviceName;
            this.group = group;
            this.version = version;
        }
    }
}
