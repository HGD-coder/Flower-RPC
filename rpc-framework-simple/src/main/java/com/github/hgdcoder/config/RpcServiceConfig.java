package com.github.hgdcoder.config;

import com.github.hgdcoder.utils.RpcServiceNameBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 描述一个准备提供给远程客户端调用的 RPC 服务。
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class RpcServiceConfig {

    /**
     * 可选的原始服务接口名。
     *
     * 普通对象可以通过 service.getClass().getInterfaces()
     * 找到服务接口。
     *
     * 但是 Spring Bean 被 AOP 代理后，运行时对象的类型
     * 可能已经不是原来的业务实现类，所以允许提前保存接口名。
     */
    private String serviceName;

    /**
     * 服务版本。
     *
     * 同一个接口的多个版本可以同时存在。
     */
    @Builder.Default
    private String version = "";

    /**
     * 服务分组。
     *
     * 同一个接口的不同实现可以通过 group 进行区分。
     */
    @Builder.Default
    private String group = "";

    /**
     * 真正执行方法调用的业务对象。
     *
     * 例如：
     * new HelloServiceImpl()
     */
    private Object service;

    /**
     * 生成注册中心、本地服务容器共同使用的完整服务键。
     */
    public String getRpcServiceName() {
        return RpcServiceNameBuilder.build(
                getServiceName(),
                group,
                version
        );
    }

    /**
     * 获得 RPC 服务接口名称。
     *
     * 获取顺序：
     *
     * 1. 优先使用显式保存的 serviceName；
     * 2. 没有填写时，从业务实现类的第一个接口推导。
     */
    public String getServiceName() {
        if (serviceName != null) {
            return serviceName;
        }

        /*
         * 没有显式接口名时，必须提供真实业务对象，
         * 否则无法推导服务接口。
         */
        if (service == null) {
            throw new IllegalArgumentException(
                    "Rpc service must not be null "
                            + "when serviceName is absent"
            );
        }

        Class<?>[] interfaces =
                service.getClass().getInterfaces();

        /*
         * RPC 客户端依赖接口创建动态代理。
         * 服务实现类如果没有实现接口，就没有稳定的远程契约。
         */
        if (interfaces.length == 0) {
            throw new IllegalArgumentException(
                    "Rpc service must implement an interface"
            );
        }

        /*
         * 目前仍沿用项目原来的约定：
         * 第一个接口就是需要发布的 RPC 接口。
         */
        return interfaces[0].getName();
    }
}
