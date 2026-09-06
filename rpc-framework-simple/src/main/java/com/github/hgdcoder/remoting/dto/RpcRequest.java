package com.github.hgdcoder.remoting.dto;

import com.github.hgdcoder.utils.RpcServiceNameBuilder;
import lombok.*;

import java.io.Serializable;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@ToString
public class RpcRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 业务请求编号。
     *
     * 用于将 RpcResponse 和对应的 RpcRequest 关联起来。
     */
    private String requestId;

    /**
     * 远程服务接口的全限定名。
     *
     * 例如：
     * com.github.hgdcoder.HelloService
     */
    private String interfaceName;

    /**
     * 准备调用的方法名。
     */
    private String methodName;

    /**
     * 调用方法时传入的实际参数。
     */
    private Object[] parameters;

    /**
     * 方法参数的类型。
     *
     * 服务端通过它找到准确的重载方法。
     */
    private Class<?>[] paramTypes;

    /**
     * 目标服务版本。
     */
    private String version;

    /**
     * 目标服务分组。
     */
    private String group;

    /**
     * 生成本次请求对应的完整 RPC 服务键。
     *
     * 请求端和服务端必须使用同一套生成规则，
     * 否则客户端查询不到服务端注册的地址。
     */
    public String getRpcServiceName() {
        return RpcServiceNameBuilder.build(
                interfaceName,
                group,
                version
        );
    }

}
