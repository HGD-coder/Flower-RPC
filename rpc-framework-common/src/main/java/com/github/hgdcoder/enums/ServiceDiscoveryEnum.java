package com.github.hgdcoder.enums;

/**
 * Flower-RPC 内置的服务发现扩展名。
 *
 * <p>服务注册和服务发现分别配置，
 * 因此客户端和服务端可以独立选择实现。</p>
 */
public enum ServiceDiscoveryEnum {

    /** 从 ZooKeeper 查询服务地址。 */
    ZK("zk"),

    /** 从本地注册文件查询服务地址。 */
    FILE("file");

    private final String name;

    ServiceDiscoveryEnum(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}