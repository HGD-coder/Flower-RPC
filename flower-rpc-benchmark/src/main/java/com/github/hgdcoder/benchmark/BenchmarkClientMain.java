package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.extension.RpcExtensionFactory;
import com.github.hgdcoder.registry.ServiceDiscovery;

/** 运行一个可配置的压测场景。 */
public final class BenchmarkClientMain {
    private BenchmarkClientMain() {
    }

    public static void main(String[] args) throws Exception {
        BenchmarkOptions options = BenchmarkOptions.parse(args);
        RpcFrameworkConfig config = RpcFrameworkConfig.load();
        BenchmarkMainSupport.printConfiguration(config, options);

        ServiceDiscovery discovery = RpcExtensionFactory.createServiceDiscovery(config);
        try {
            BenchmarkResult result = new BenchmarkRunner(config, discovery).run(options);
            result.print();
            System.out.println("resultFile=" + options.getOutput().toAbsolutePath().normalize());
        } finally {
            BenchmarkMainSupport.closeDiscoveryResources(config);
        }
    }
}
