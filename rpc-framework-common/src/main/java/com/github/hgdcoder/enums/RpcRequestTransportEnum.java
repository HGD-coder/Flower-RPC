package com.github.hgdcoder.enums;

/**
 * Flower-RPC 内置的客户端请求传输扩展名。
 */
public enum RpcRequestTransportEnum {

    /** 基于 Netty 的异步网络传输。 */
    NETTY("netty"),

    /** 基于原生阻塞 Socket 的网络传输。 */
    SOCKET("socket");

    private final String name;

    RpcRequestTransportEnum(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}