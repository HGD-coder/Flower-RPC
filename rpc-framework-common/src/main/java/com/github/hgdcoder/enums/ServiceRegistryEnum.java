package com.github.hgdcoder.enums;

/**
 * Flower-RPC 内置的服务注册扩展名。
 *
 * <p>枚举只记录框架自带实现，配置类仍允许填写第三方扩展名。</p>
 */
public enum ServiceRegistryEnum {

    /** 使用 ZooKeeper 注册服务。 */
    ZK("zk"),

    /** 使用本地文件注册服务。 */
    FILE("file");

    /**
     * 对应 SPI 配置文件等号左边的名字。
     */
    private final String name;

    ServiceRegistryEnum(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}