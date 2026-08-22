package com.github.hgdcoder.spring;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.annotation.Annotation;

/**
 * 只识别指定注解的类路径扫描器。
 *
 * <p>Spring 在配置阶段扫描类路径时，默认会按 {@code @Component} 等标准组件注解
 * 选取候选类。本类关闭该默认规则，转而只接受构造参数指定的注解。</p>
 *
 * <p>这样普通 Spring 组件和 RPC 服务可由不同实例分别扫描，{@code @RpcService}
 * 无需同时标记为 {@code @Component}。</p>
 *
 * <p>它只负责“发现类并登记 BeanDefinition”，此时对象还没有被创建。
 * 真正的 Bean 实例会在后续容器初始化阶段创建，并交给
 * {@link SpringBeanPostProcessor} 处理 RPC 注解。</p>
 */
public class CustomScanner extends ClassPathBeanDefinitionScanner {
    /**
     * 创建只匹配 {@code annotationType} 的扫描器。
     *
     * @param registry Spring Bean 定义注册表；扫描到的候选类将以 BeanDefinition 形式注册到这里
     * @param annotationType 要识别的注解类型，例如 {@code Component.class} 或 {@code RpcService.class}
     */
    public CustomScanner(
            BeanDefinitionRegistry registry,
            Class<? extends Annotation> annotationType
    ) {
        // false 表示不启用 Spring 默认的组件过滤器，避免扫描职责与另一个扫描器重叠。
        super(registry,false);
        // 将指定注解加入“包含”规则。scan() 遍历包时，会用该过滤器判断每个类是否应注册。
        addIncludeFilter(new AnnotationTypeFilter(annotationType));
    }
}
