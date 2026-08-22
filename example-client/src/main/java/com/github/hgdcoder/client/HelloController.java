package com.github.hgdcoder.client;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;
import com.github.hgdcoder.annotation.RpcReference;
import org.springframework.stereotype.Component;

/**
 * 示例业务组件；字段在初始化前被替换成连接现有 RpcClientProxy 调用链的 JDK 代理。
 */
@Component
public class HelloController {
    @RpcReference(group="test",version = "1.0")
    private HelloService helloService;

    public String callHelloService() {
        return helloService.hello(new Hello("Flower","RPC V13 Spring"));
    }
}
