package com.github.hgdcoder;

import java.util.concurrent.CompletableFuture;

/**
 * HelloService 的异步镜像接口。
 *
 * <p>方法名和参数必须与 HelloService 一致，区别只是返回值包在
 * CompletableFuture 中。服务端仍然只实现并注册 HelloService。</p>
 */
public interface HelloServiceAsync {
    /**
     * 发起调用后立即返回 Future，调用线程不需要停在这里等待网络响应。
     */
    CompletableFuture<String> hello(Hello hello);
}
