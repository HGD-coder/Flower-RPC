package com.github.hgdcoder.annotation;

import java.lang.annotation.*;

/**
 * 标记需要注入 JDK 动态代理的 RPC 接口字段。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Inherited
public @interface RpcReference {
    /**
     * 远程服务版本，参与现有 RPC 服务键和服务发现。
     */
    String version() default "";

    /**
     * 远程服务分组，参与现有 RPC 服务键和服务发现。
     */
    String group() default "";
}
