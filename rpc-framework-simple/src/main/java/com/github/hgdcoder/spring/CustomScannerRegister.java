package com.github.hgdcoder.spring;

import com.github.hgdcoder.annotation.RpcScan;
import com.github.hgdcoder.annotation.RpcService;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link RpcScan} 的 Spring 导入注册器。
 *
 * <p>当 Spring 解析到 {@code @RpcScan} 所触发的 {@code @Import} 时，会回调本类，
 * 由本类在容器刷新早期向注册表补充 BeanDefinition。它不直接创建 Bean，而是先声明
 * 哪些类应由后续的 Spring 生命周期管理。</p>
 *
 * <p>同一组基础包会被分别扫描：标准 {@code @Component} 类走 Spring 组件扫描，
 * {@code @RpcService} 类走 RPC 服务扫描。</p>
 *
 * <p>完整位置：{@code @RpcScan -> @Import -> 本注册器 -> CustomScanner ->
 * BeanDefinitionRegistry}。完成这里的工作后，Spring 才会根据 BeanDefinition 创建对象。</p>
 */
public class CustomScannerRegister implements ImportBeanDefinitionRegistrar , ResourceLoaderAware {
    /** {@link RpcScan} 中保存基础包列表的属性名，集中为常量以避免字符串散落在解析逻辑中。 */
    private static final String BASE_PACKAGE_ATTRIBUTE = "basePackage";

    /**
     * Spring 注入的资源加载器，供扫描器解析类路径及其他资源时使用。
     * 当注册器由非标准方式调用而未注入时，扫描仍可使用 Spring 默认行为。
     */
    private ResourceLoader resourceLoader;

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        // ResourceLoaderAware 是“感知接口”：Spring 发现本类实现它后，会主动把容器的资源加载器传进来。
        // 保存后再交给扫描器，使扫描器能够用与当前 ApplicationContext 相同的方式查找 classpath 资源。
        this.resourceLoader = resourceLoader;
    }

    /**
     * 在 Spring 处理 {@code @Import} 时注册扫描结果。
     *
     * @param importingClassMetadata 标注 {@code @RpcScan} 的配置类元数据，无需加载该类即可读取注解
     * AnnotationMetadata
     * └── @RpcScan
     *     └── basePackage =
     *         "com.github.hgdcoder.server"
     * @param registry 当前应用上下文的 BeanDefinition 注册表
     * 将上面的basePackage内有@Component和@RpcService注释的类注册到BeanDefinition
     */
    @Override
    public void registerBeanDefinitions(
            AnnotationMetadata importingClassMetadata,
            BeanDefinitionRegistry registry
    ){
        // 先统一确定扫描边界，保证两类扫描器覆盖同一批包。
        String[] basePackages = resolveBasePackages(importingClassMetadata);

        // 两个扫描器共享完全相同的包边界：一个承接 Spring 组件，另一个承接纯 RPC 服务。
        // 第一个扫描器匹配 @Component，负责配置类、控制器等普通 Spring Bean。
        CustomScanner componentScanner = new CustomScanner(registry, Component.class);
        // 第二个扫描器匹配 @RpcService，使 RPC 服务即使没有 @Component 也能进入 Spring 容器。
        CustomScanner rpcServiceScanner = new CustomScanner(registry, RpcService.class);
        if (resourceLoader != null) {
            componentScanner.setResourceLoader(resourceLoader);
            rpcServiceScanner.setResourceLoader(resourceLoader);
        }

        // scan() 只注册 BeanDefinition，不会在这里直接 new 出业务对象。
        componentScanner.scan(basePackages);
        rpcServiceScanner.scan(basePackages);
    }

    /**
     * 从 {@link RpcScan} 解析扫描包；注解未配置有效包时，回退到配置类所在包。
     *
     * @param metadata {@code @RpcScan} 所在配置类的元数据
     * AnnotationMetadata
     * └── @RpcScan
     *     └── basePackage =
     *         "com.github.hgdcoder.server"
     * @return 已去除空白项的基础包数组，至少包含一个可用于扫描的包名
     * basePackages = {
     *     "com.github.hgdcoder.server"
     * };
     * @throws IllegalArgumentException 配置类位于默认包且未显式指定基础包时抛出，因为无从安全推断扫描范围
     */
    private String[] resolveBasePackages(AnnotationMetadata metadata) {
        // AnnotationAttributes 让属性读取保留 Spring 的注解类型信息；没有注解属性时按空配置处理。
        // metadata 描述“哪个配置类导入了本注册器”；从中读取该类上的 @RpcScan 参数。
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                metadata.getAnnotationAttributes(RpcScan.class.getName())
        );
        // 注解属性可能不存在，因此用空数组统一后续遍历逻辑。
        String[] configuredPackages = attributes == null
                ? new String[0]
                : attributes.getStringArray(BASE_PACKAGE_ATTRIBUTE);

        // 忽略空字符串，并在返回前去掉用户配置中常见的首尾空格。
        List<String> nonEmptyPackages = new ArrayList<>();
        for (String configuredPackage : configuredPackages) {
            if (StringUtils.hasText(configuredPackage)) {
                nonEmptyPackages.add(configuredPackage.trim());
            }
        }
        if (!nonEmptyPackages.isEmpty()) {
            return nonEmptyPackages.toArray(new String[0]);
        }

        // AnnotationMetadata 不一定来自已加载的 Class，按类名推断可兼容 Spring 的元数据读取路径。
        String inferredPackage = ClassUtils.getPackageName(metadata.getClassName());
        if (!StringUtils.hasText(inferredPackage)) {
            throw new IllegalArgumentException(
                    "@RpcScan cannot infer a base package from the default package: "
                            + metadata.getClassName()
            );
        }
        return new String[]{inferredPackage};
    }

}
