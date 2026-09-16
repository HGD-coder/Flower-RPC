package com.github.hgdcoder.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记可能长时间阻塞的 RPC 服务方法。
 *
 * <p>标记的方法进入 slowBusinessExecutor；
 * 没有标记的方法默认进入 fastBusinessExecutor。</p>
 *
 * <p>分类发生在服务注册阶段，请求到达后只查询内存缓存，
 * 不会在 Netty EventLoop 中执行反射扫描。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RpcSlow {
}