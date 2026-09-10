package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.registry.zk.CuratorUtils;

/** 两个命令行入口共享的启动与清理逻辑。 */
final class BenchmarkMainSupport {
    private BenchmarkMainSupport() {
    }

    static void printConfiguration(RpcFrameworkConfig config, BenchmarkOptions options) {
        System.out.println("mode=" + options.getMode().name().toLowerCase());
        System.out.println("transport=" + config.getTransport());
        System.out.println("discovery=" + config.getDiscovery());
        System.out.println("loadBalance=" + config.getLoadBalance());
        System.out.println("serializer=" + config.getSerializer());
        System.out.println("compress=" + config.getCompress());
        System.out.println("connectTimeoutMillis=" + config.getConnectTimeoutMillis());
        System.out.println("requestTimeoutMillis=" + config.getRequestTimeoutMillis());
        System.out.println("payloadType=" + options.getPayloadType().name().toLowerCase());
        System.out.println("seed=" + options.getSeed());
    }

    static void closeDiscoveryResources(RpcFrameworkConfig config) {
        if (RpcFrameworkConfig.DISCOVERY_ZK.equals(config.getDiscovery())) {
            CuratorUtils.closeZkClient();
        }
    }
}
