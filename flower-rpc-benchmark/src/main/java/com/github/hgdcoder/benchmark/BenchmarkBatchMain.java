package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;
import com.github.hgdcoder.loadbalance.loadbalancer.ConsistentHashLoadBalance;
import com.github.hgdcoder.proxy.RpcClientProxy;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.registry.zk.CuratorUtils;
import com.github.hgdcoder.registry.zk.ZkServiceDiscovery;
import com.github.hgdcoder.remoting.constants.RpcConstants;
import com.github.hgdcoder.transport.netty.client.NettyRpcClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 以当前默认压缩方式串行执行固定矩阵的 RPC 客户端压测。
 */
public class BenchmarkBatchMain {
    private static final int[] PAYLOAD_BYTES = {9, 65536};
    private static final int[] THREADS = {1, 2, 4, 8};

    public static void main(String[] args) throws Exception {
        int durationSeconds = positiveIntArg(args, "--duration", 30);
        int warmupSeconds = nonNegativeIntArg(args, "--warmup", 5);
        int repeats = positiveIntArg(args, "--repeats", 1);
        String compression = compressName(RpcConstants.DEFAULT_COMPRESS);

        System.out.println("Benchmark batch compress=" + compression);
        System.out.println("duration=" + durationSeconds + "s, warmup=" + warmupSeconds
                + "s, repeats=" + repeats);

        // 所有场次复用同一服务发现实例和 Curator 单例，避免将注册中心建连计入场次差异。
        ServiceDiscovery serviceDiscovery = new ZkServiceDiscovery(new ConsistentHashLoadBalance());
        List<BenchmarkResult> results = new ArrayList<>();
        try {
            for (int payloadBytes : PAYLOAD_BYTES) {
                String payload = repeatedAsciiPayload(payloadBytes);
                for (int threads : THREADS) {
                    for (int repeat = 1; repeat <= repeats; repeat++) {
                        System.out.println();
                        System.out.println("============================================================");
                        System.out.println("payloadBytes=" + payloadBytes
                                + ", threads=" + threads
                                + ", repeat=" + repeat
                                + ", compress=" + compression);
                        BenchmarkResult result = runScenario(
                                serviceDiscovery,
                                payload,
                                payloadBytes,
                                threads,
                                durationSeconds,
                                warmupSeconds,
                                repeat
                        );
                        results.add(result);
                        printResult(result);
                    }
                }
            }
        } finally {
            CuratorUtils.closeZkClient();
        }

        printSummary(compression, results);
    }

    private static BenchmarkResult runScenario(
            ServiceDiscovery serviceDiscovery,
            String payload,
            int payloadBytes,
            int threads,
            int durationSeconds,
            int warmupSeconds,
            int repeat
    ) throws Exception {
        NettyRpcClient client = new NettyRpcClient(serviceDiscovery);
        try {
            RpcClientProxy proxy = new RpcClientProxy(client, "test", "1.0");
            HelloService helloService = proxy.getProxy(HelloService.class);

            warmup(helloService, payload, warmupSeconds);
            // 正式统计前关闭预热连接，并清零连接创建、复用计数。
            client.closeConnections();
            client.resetConnectionStatistics();

            return measure(
                    helloService,
                    client,
                    payload,
                    payloadBytes,
                    threads,
                    durationSeconds,
                    repeat
            );
        } finally {
            client.close();
        }
    }

    private static void warmup(HelloService helloService, String payload, int warmupSeconds) {
        System.out.println("Warmup " + warmupSeconds + "s...");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(warmupSeconds);
        while (System.nanoTime() < end) {
            helloService.hello(new Hello("warmup", payload));
        }
    }

    private static BenchmarkResult measure(
            HelloService helloService,
            NettyRpcClient client,
            String payload,
            int payloadBytes,
            int threads,
            int durationSeconds,
            int repeat
    ) throws Exception {
        System.out.println("Benchmark running " + durationSeconds
                + "s, threads=" + threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(threads);
            AtomicBoolean running = new AtomicBoolean(true);
            LongAdder success = new LongAdder();
            LongAdder failed = new LongAdder();
            LongAdder totalLatency = new LongAdder();
            ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

            for (int index = 0; index < threads; index++) {
                pool.execute(() -> runWorker(
                        helloService,
                        payload,
                        startGate,
                        doneGate,
                        running,
                        success,
                        failed,
                        totalLatency,
                        latencies
                ));
            }

            long begin = System.nanoTime();
            startGate.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(durationSeconds));
            } finally {
                running.set(false);
            }
            doneGate.await();
            long elapsedNanos = System.nanoTime() - begin;

            List<Long> sortedLatencies = new ArrayList<>(latencies);
            Collections.sort(sortedLatencies);
            long successfulRequests = success.sum();
            long created = client.getCreatedConnectionCount();
            long reused = client.getReusedConnectionCount();
            long lookups = created + reused;

            return new BenchmarkResult(
                    payloadBytes,
                    threads,
                    repeat,
                    successfulRequests,
                    failed.sum(),
                    successfulRequests / (elapsedNanos / 1_000_000_000.0),
                    nsToMs(successfulRequests == 0 ? 0 : totalLatency.sum() / successfulRequests),
                    nsToMs(percentile(sortedLatencies, 50)),
                    nsToMs(percentile(sortedLatencies, 95)),
                    nsToMs(percentile(sortedLatencies, 99)),
                    nsToMs(sortedLatencies.isEmpty() ? 0 : sortedLatencies.get(sortedLatencies.size() - 1)),
                    created,
                    reused,
                    lookups == 0 ? 0 : reused * 100.0 / lookups
            );
        } finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("Benchmark worker pool did not stop within 5 seconds.");
            }
        }
    }

    private static void runWorker(
            HelloService helloService,
            String payload,
            CountDownLatch startGate,
            CountDownLatch doneGate,
            AtomicBoolean running,
            LongAdder success,
            LongAdder failed,
            LongAdder totalLatency,
            ConcurrentLinkedQueue<Long> latencies
    ) {
        try {
            startGate.await();
            while (running.get()) {
                long start = System.nanoTime();
                try {
                    String result = helloService.hello(new Hello("Flower", payload));
                    long cost = System.nanoTime() - start;
                    if (result != null) {
                        success.increment();
                        totalLatency.add(cost);
                        latencies.add(cost);
                    } else {
                        failed.increment();
                    }
                } catch (Exception ignored) {
                    failed.increment();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doneGate.countDown();
        }
    }

    private static void printResult(BenchmarkResult result) {
        System.out.println("payloadBytes=" + result.payloadBytes + ", threads=" + result.threads
                + ", repeat=" + result.repeat);
        System.out.println("success=" + result.success);
        System.out.println("failed=" + result.failed);
        System.out.println("qps=" + format(result.qps));
        System.out.println("avg=" + format(result.avgMs) + "ms");
        System.out.println("p50=" + format(result.p50Ms) + "ms");
        System.out.println("p95=" + format(result.p95Ms) + "ms");
        System.out.println("p99=" + format(result.p99Ms) + "ms");
        System.out.println("max=" + format(result.maxMs) + "ms");
        System.out.println("created=" + result.created);
        System.out.println("reused=" + result.reused);
        System.out.println("reuseRate=" + format(result.reuseRate) + "%");
    }

    private static void printSummary(String compression, List<BenchmarkResult> results) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("Summary compress=" + compression);
        System.out.println("payloadBytes threads repeat success failed qps avgMs p50Ms p95Ms p99Ms maxMs created reused reuseRate");
        for (BenchmarkResult result : results) {
            System.out.println(result.payloadBytes + " " + result.threads + " " + result.repeat
                    + " " + result.success + " " + result.failed
                    + " " + format(result.qps)
                    + " " + format(result.avgMs)
                    + " " + format(result.p50Ms)
                    + " " + format(result.p95Ms)
                    + " " + format(result.p99Ms)
                    + " " + format(result.maxMs)
                    + " " + result.created
                    + " " + result.reused
                    + " " + format(result.reuseRate) + "%");
        }
    }

    private static int positiveIntArg(String[] args, String name, int defaultValue) {
        int value = intArg(args, name, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int nonNegativeIntArg(String[] args, String name, int defaultValue) {
        int value = intArg(args, name, defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static int intArg(String[] args, String name, int defaultValue) {
        for (String arg : args) {
            if (arg.startsWith(name + "=")) {
                return Integer.parseInt(arg.substring((name + "=").length()));
            }
        }
        return defaultValue;
    }

    private static String repeatedAsciiPayload(int payloadBytes) {
        char[] chars = new char[payloadBytes];
        Arrays.fill(chars, 'F');
        return new String(chars);
    }

    private static long percentile(List<Long> sorted, int percentage) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(sorted.size() * percentage / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String compressName(byte compressType) {
        if (compressType == RpcConstants.NO_COMPRESS) {
            return "none";
        }
        if (compressType == RpcConstants.GZIP_COMPRESS) {
            return "gzip";
        }
        return "unknown(" + compressType + ")";
    }

    private static double nsToMs(long nanoseconds) {
        return nanoseconds / 1_000_000.0;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static final class BenchmarkResult {
        private final int payloadBytes;
        private final int threads;
        private final int repeat;
        private final long success;
        private final long failed;
        private final double qps;
        private final double avgMs;
        private final double p50Ms;
        private final double p95Ms;
        private final double p99Ms;
        private final double maxMs;
        private final long created;
        private final long reused;
        private final double reuseRate;

        private BenchmarkResult(
                int payloadBytes,
                int threads,
                int repeat,
                long success,
                long failed,
                double qps,
                double avgMs,
                double p50Ms,
                double p95Ms,
                double p99Ms,
                double maxMs,
                long created,
                long reused,
                double reuseRate
        ) {
            this.payloadBytes = payloadBytes;
            this.threads = threads;
            this.repeat = repeat;
            this.success = success;
            this.failed = failed;
            this.qps = qps;
            this.avgMs = avgMs;
            this.p50Ms = p50Ms;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
            this.maxMs = maxMs;
            this.created = created;
            this.reused = reused;
            this.reuseRate = reuseRate;
        }
    }
}
