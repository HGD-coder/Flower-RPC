package com.github.hgdcoder.loadbalance;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.extension.ExtensionLoader;

import java.util.Locale;

/** 通过 Flower-RPC SPI 名称创建负载均衡实现，装配层无需依赖具体实现类。 */
public final class LoadBalanceFactory {
    private LoadBalanceFactory() {
    }

    public static LoadBalance create(RpcFrameworkConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        return create(config.getLoadBalance());
    }

    public static LoadBalance create(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("load balance name must not be empty");
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return ExtensionLoader.getExtensionLoader(LoadBalance.class)
                .getExtension(normalized);
    }
}
