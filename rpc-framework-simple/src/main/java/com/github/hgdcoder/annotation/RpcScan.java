package com.github.hgdcoder.annotation;

import com.github.hgdcoder.spring.CustomScannerRegister;
import com.github.hgdcoder.spring.SpringBeanPostProcessor;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 开启普通 Spring 组件与 RPC 服务的联合扫描，并安装 RPC 生命周期处理器。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE,ElementType.METHOD})
@Import({CustomScannerRegister.class, SpringBeanPostProcessor.class})
public @interface RpcScan {
    /**
     * 扫描包；未配置或只配置空白值时使用声明本注解的配置类所在包。
     */
    String[] basePackage() default {};
}
