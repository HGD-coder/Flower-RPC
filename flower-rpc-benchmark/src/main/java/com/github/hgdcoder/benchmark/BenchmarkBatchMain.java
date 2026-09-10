package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.extension.RpcExtensionFactory;
import com.github.hgdcoder.registry.ServiceDiscovery;

/**
 * 串行运行压测矩阵。每完成一场就立即写入 CSV，因此中途停止不会丢失之前结果。
 */
public final class BenchmarkBatchMain {
    private static final int[] DEFAULT_PAYLOADS = {9, 65536};
    private static final int[] DEFAULT_THREADS = {1, 2, 4, 8};
    private static final long[] DEFAULT_RATES = {1000L, 5000L, 10000L};

    private BenchmarkBatchMain() {
    }

    public static void main(String[] args) throws Exception {
        BenchmarkOptions base = BenchmarkOptions.parse(args);
        int[] payloads = BenchmarkOptions.intList(args, "payloads", DEFAULT_PAYLOADS);
        int[] threads = BenchmarkOptions.intList(args, "threads-list", DEFAULT_THREADS);
        long[] rates = BenchmarkOptions.longList(args, "rates", DEFAULT_RATES);
        int repeats = BenchmarkOptions.repeats(args);

        RpcFrameworkConfig config = RpcFrameworkConfig.load();
        BenchmarkMainSupport.printConfiguration(config, base);
        ServiceDiscovery discovery = RpcExtensionFactory.createServiceDiscovery(config);
        BenchmarkRunner runner = new BenchmarkRunner(config, discovery);

        try {
            for (int payload : payloads) {
                if (base.getMode() == BenchmarkOptions.Mode.CONCURRENCY) {
                    for (int threadCount : threads) {
                        for (int repeat = 1; repeat <= repeats; repeat++) {
                            runScenario(runner, base.scenario(
                                    payload, threadCount, base.getRate(), repeat
                            ));
                        }
                    }
                } else {
                    for (long rate : rates) {
                        for (int repeat = 1; repeat <= repeats; repeat++) {
                            runScenario(runner, base.scenario(
                                    payload, base.getThreads(), rate, repeat
                            ));
                        }
                    }
                }
            }
        } finally {
            BenchmarkMainSupport.closeDiscoveryResources(config);
        }
        System.out.println("resultFile=" + base.getOutput().toAbsolutePath().normalize());
    }

    private static void runScenario(BenchmarkRunner runner, BenchmarkOptions options)
            throws Exception {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("mode=" + options.getMode().name().toLowerCase()
                + ", payloadBytes=" + options.getPayloadBytes()
                + ", threads=" + options.getThreads()
                + ", rate=" + options.getRate()
                + ", repeat=" + options.getRepeat());
        runner.run(options).print();
    }
}
