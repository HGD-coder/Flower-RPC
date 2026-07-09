package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;
import com.github.hgdcoder.proxy.RpcClientProxy;
import com.github.hgdcoder.transport.socket.SocketRpcClient;

import java.util.ArrayList;
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

public class BenchmarkClientMain {
    public static void main(String[] args) throws Exception {
        int threads = intArg(args, "--threads", 4);
        int durationSeconds = intArg(args, "--duration", 30);
        int warmupSeconds = intArg(args, "--warmup", 5);

        SocketRpcClient client = new SocketRpcClient("127.0.0.1", 9998);
        RpcClientProxy proxy = new RpcClientProxy(client, "test", "1.0");
        HelloService helloService = proxy.getProxy(HelloService.class);

        System.out.println("Warmup " + warmupSeconds + "s...");
        long warmupEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(warmupSeconds);
        while (System.nanoTime() < warmupEnd) {
            helloService.hello(new Hello("warmup", "benchmark"));
        }
        client.closeCurrentConnection();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicBoolean running = new AtomicBoolean(true);

        LongAdder success = new LongAdder();
        LongAdder failed = new LongAdder();
        LongAdder totalLatency = new LongAdder();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    startGate.await();
                    while (running.get()) {
                        long start = System.nanoTime();
                        try {
                            String result = helloService.hello(new Hello("Flower", "Benchmark"));
                            long cost = System.nanoTime() - start;

                            if (result != null) {
                                success.increment();
                                totalLatency.add(cost);
                                latencies.add(cost);
                            } else {
                                failed.increment();
                            }
                        } catch (Exception e) {
                            failed.increment();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    client.closeCurrentConnection();
                    doneGate.countDown();
                }
            });
        }

        System.out.println("Benchmark running " + durationSeconds + "s, threads=" + threads);
        long begin = System.nanoTime();
        startGate.countDown();
        Thread.sleep(durationSeconds * 1000L);
        running.set(false);
        doneGate.await();
        long elapsed = System.nanoTime() - begin;
        pool.shutdown();

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        long ok = success.sum();
        long fail = failed.sum();
        double seconds = elapsed / 1_000_000_000.0;

        System.out.println("success=" + ok);
        System.out.println("failed=" + fail);
        System.out.println("qps=" + format(ok / seconds));
        System.out.println("avgMs=" + format(nsToMs(ok == 0 ? 0 : totalLatency.sum() / ok)));
        System.out.println("p50Ms=" + format(nsToMs(percentile(sorted, 50))));
        System.out.println("p95Ms=" + format(nsToMs(percentile(sorted, 95))));
        System.out.println("p99Ms=" + format(nsToMs(percentile(sorted, 99))));
        System.out.println("maxMs=" + format(nsToMs(sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1))));
    }

    private static int intArg(String[] args, String name, int defaultValue) {
        for (String arg : args) {
            if (arg.startsWith(name + "=")) {
                return Integer.parseInt(arg.substring((name + "=").length()));
            }
        }
        return defaultValue;
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }

        int index = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double nsToMs(long ns) {
        return ns / 1_000_000.0;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
