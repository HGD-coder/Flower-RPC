package com.github.hgdcoder.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 统一构造客户端、服务端和注册中心使用的 RPC 服务键。
 *
 * <p>分组和版本先按 UTF-8 编码，再转成不带填充的 Base64Url，
 * 避免字段直接拼接产生歧义，也不会把 ZooKeeper 路径分隔符
 * 带进最终的服务键。</p>
 */
public class RpcServiceNameBuilder {

    /*
     * 工具类不需要创建对象，因此构造方法设为 private。
     */
    private RpcServiceNameBuilder() {
    }

    /**
     * 按照以下格式生成 RPC 服务键：
     *
     * interfaceName:Base64Url(group):Base64Url(version)
     *
     * 例如：
     *
     * group   = test
     * version = 1.0
     *
     * 最终结果类似：
     *
     * com.github.hgdcoder.HelloService:dGVzdA:MS4w
     */
    public static String build(
            String interfaceName,
            String group,
            String version
    ) {
        validateInterfaceName(interfaceName);

        return interfaceName
                + ':'
                + encode(group)
                + ':'
                + encode(version);
    }

    /**
     * 将 group 或 version 转换成适合放入服务键的字符串。
     *
     * null 与空字符串按照相同方式处理。
     *
     * withoutPadding() 表示删除 Base64 末尾的等号，
     * 让生成的服务键更简洁。
     */
    private static String encode(String value) {
        String normalized = value == null ? "" : value;

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        normalized.getBytes(StandardCharsets.UTF_8)
                );
    }

    /**
     * 接口名会成为注册中心路径的一部分，因此必须提前校验。
     */
    private static void validateInterfaceName(
            String interfaceName
    ) {
        if (interfaceName == null
                || interfaceName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "interfaceName must not be empty"
            );
        }

        /*
         * "/" 是 ZooKeeper 的路径分隔符；
         * ":" 是我们自己使用的字段分隔符；
         * NUL 是不应该出现在服务名称中的特殊字符。
         */
        if (interfaceName.indexOf('/') >= 0
                || interfaceName.indexOf(':') >= 0
                || interfaceName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "interfaceName must not contain '/', ':' or NUL"
            );
        }
    }
}
