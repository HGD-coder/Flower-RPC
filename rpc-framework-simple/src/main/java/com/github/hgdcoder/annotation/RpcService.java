package com.github.hgdcoder.annotation;

import java.lang.annotation.*;

/**
 * 标记需要由 RPC 框架发布的 Spring 服务实现。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface RpcService {
    /**
     * 同一接口的服务版本；空字符串保持现有手动 API 的默认语义。
     */
    String version() default "";

    /**
     * 同一接口的服务分组；空字符串保持现有手动 API 的默认语义。
     */
    String group() default "";

}
