package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.Hello;
import com.github.hgdcoder.HelloService;
import com.github.hgdcoder.HelloServiceAsync;
import com.github.hgdcoder.config.RpcFrameworkConfig;
import com.github.hgdcoder.extension.RpcExtensionFactory;
import com.github.hgdcoder.proxy.RpcClientProxy;
import com.github.hgdcoder.registry.ServiceDiscovery;
import com.github.hgdcoder.transport.RpcRequestTransport;
import com.github.hgdcoder.transport.netty.client.NettyRpcClient;
import com.github.hgdcoder.transport.socket.SocketRpcClient;

import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** 单场压测的共享运行引擎。 */
final class BenchmarkRunner {
    private final RpcFrameworkConfig config;
    private final ServiceDiscovery serviceDiscovery;

    BenchmarkRunner(RpcFrameworkConfig config, ServiceDiscovery serviceDiscovery) {
        this.config = config;
        this.serviceDiscovery = serviceDiscovery;
    }

    BenchmarkResult run(BenchmarkOptions options) throws Exception {
        RpcRequestTransport transport = RpcExtensionFactory
                .createRpcRequestTransport(serviceDiscovery, config);
        try {
            RpcClientProxy proxy = new RpcClientProxy(transport, "test", "1.0");
            HelloService syncService = proxy.getProxy(HelloService.class);
            HelloServiceAsync asyncService = proxy.getAsyncProxy(
                    HelloServiceAsync.class,
                    HelloService.class
            );
            String payload = payload(options);

            warmup(syncService, payload, options.getWarmupSeconds());

            // 不关闭预热连接。用前后快照做差，只统计稳态测量阶段的连接行为。
            ConnectionCounts before = connectionCounts(transport);
            BenchmarkRuntimeSnapshot runtimeBefore = BenchmarkRuntimeSnapshot.capture();
            BenchmarkMetrics metrics = new BenchmarkMetrics();
            long elapsedNanos;
            if (options.getMode() == BenchmarkOptions.Mode.CONCURRENCY) {
                elapsedNanos = runConcurrency(syncService, payload, options, metrics);
            } else {
                elapsedNanos = runRate(asyncService, payload, options, metrics);
            }
            ConnectionCounts after = connectionCounts(transport);
            BenchmarkRuntimeSnapshot runtimeAfter = BenchmarkRuntimeSnapshot.capture();

            BenchmarkResult result = new BenchmarkResult(
                    options,
                    config,
                    metrics.snapshot(),
                    elapsedNanos,
                    after.created - before.created,
                    after.reused - before.reused,
                    runtimeBefore,
                    runtimeAfter
            );
            BenchmarkCsvWriter.append(options.getOutput(), result);
            return result;
        } finally {
            transport.close();
        }
    }

    /**
     * 闭环模式：每个线程只有在上一次调用结束后才发起下一次调用。
     * 它适合观察固定并发下的最大吞吐和响应延迟。
     */
    private long runConcurrency(
            HelloService service,
            String payload,
            BenchmarkOptions options,
            BenchmarkMetrics metrics
    ) throws InterruptedException {
        int threads = options.getThreads();
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        long durationNanos = TimeUnit.SECONDS.toNanos(options.getDurationSeconds());
        AtomicLong deadline = new AtomicLong();

        try {
            for (int i = 0; i < threads; i++) {
                workers.execute(() -> {
                    try {
                        startGate.await();
                        while (System.nanoTime() < deadline.get()) {
                            long startedAt = System.nanoTime();
                            metrics.planned();
                            // 闭环模式不存在计划发送时刻，因此不记录调度延迟。
                            metrics.started(-1L);
                            BenchmarkOutcome outcome;
                            try {
                                String response = service.hello(new Hello("Flower", payload));
                                outcome = response == null
                                        ? BenchmarkOutcome.CLIENT_ERROR
                                        : BenchmarkOutcome.SUCCESS;
                            } catch (Throwable failure) {
                                outcome = BenchmarkOutcome.from(failure);
                            }
                            long completedAt = System.nanoTime();
                            metrics.completed(
                                    outcome,
                                    completedAt - startedAt,
                                    completedAt - startedAt
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            long begin = System.nanoTime();
            deadline.set(begin + durationNanos);
            startGate.countDown();
            doneGate.await();
            return System.nanoTime() - begin;
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 开环模式：按照计划时间产生请求，不等待上一个请求完成。
     * 当发压端错过发送时刻或达到 max-inflight 时，显式记为 dropped，
     * 而不是让请求悄悄消失或无限堆积。
     */
    private long runRate(
            HelloServiceAsync service,
            String payload,
            BenchmarkOptions options,
            BenchmarkMetrics metrics
    ) throws InterruptedException {
        long intervalNanos = Math.max(1L, 1_000_000_000L / options.getRate());
        long durationNanos = TimeUnit.SECONDS.toNanos(options.getDurationSeconds());
        long begin = System.nanoTime();
        long end = begin + durationNanos;
        long plannedAt = begin;
        Semaphore permits = new Semaphore(options.getMaxInflight());
        Set<CompletableFuture<String>> outstanding = ConcurrentHashMap.newKeySet();

        while (plannedAt < end) {
            long now = System.nanoTime();
            if (now < plannedAt) {
                LockSupport.parkNanos(plannedAt - now);
                now = System.nanoTime();
            }

            // 如果生产线程已经落后一个以上发送间隔，跳过过期槽位，避免补发突发流量。
            long missed = Math.max(0L, (now - plannedAt) / intervalNanos);
            long remainingSlots = Math.max(
                    0L,
                    (end - plannedAt + intervalNanos - 1L) / intervalNanos
            );
            long expiredSlots = Math.min(missed, remainingSlots);
            metrics.planned(expiredSlots);
            metrics.dropped(expiredSlots);
            plannedAt += expiredSlots * intervalNanos;
            if (plannedAt >= end) {
                break;
            }

            metrics.planned();
            long currentPlan = plannedAt;
            plannedAt += intervalNanos;
            if (!permits.tryAcquire()) {
                metrics.dropped();
                continue;
            }

            long startedAt = System.nanoTime();
            metrics.started(Math.max(0L, startedAt - currentPlan));
            CompletableFuture<String> future;
            try {
                future = service.hello(new Hello("Flower", payload));
                if (future == null) {
                    metrics.completed(
                            BenchmarkOutcome.CLIENT_ERROR,
                            System.nanoTime() - startedAt,
                            System.nanoTime() - currentPlan
                    );
                    permits.release();
                    continue;
                }
            } catch (Throwable failure) {
                long completedAt = System.nanoTime();
                metrics.completed(
                        BenchmarkOutcome.from(failure),
                        completedAt - startedAt,
                        completedAt - currentPlan
                );
                permits.release();
                continue;
            }

            outstanding.add(future);
            future.whenComplete((response, failure) -> {
                long completedAt = System.nanoTime();
                BenchmarkOutcome outcome = failure == null
                        ? (response == null ? BenchmarkOutcome.CLIENT_ERROR : BenchmarkOutcome.SUCCESS)
                        : BenchmarkOutcome.from(failure);
                metrics.completed(
                        outcome,
                        completedAt - startedAt,
                        completedAt - currentPlan
                );
                outstanding.remove(future);
                permits.release();
            });
        }

        // 停止产生新请求后，给已发出的调用一个有限排空窗口。
        long drainDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(options.getDrainTimeoutSeconds());
        while (!outstanding.isEmpty() && System.nanoTime() < drainDeadline) {
            Thread.sleep(10L);
        }
        if (!outstanding.isEmpty()) {
            for (CompletableFuture<String> future : outstanding) {
                future.cancel(true);
            }
            // cancel() 会触发完成回调；短暂等待回调释放许可并更新终态计数。
            long cancellationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!outstanding.isEmpty() && System.nanoTime() < cancellationDeadline) {
                Thread.sleep(1L);
            }
        }
        return durationNanos;
    }

    private static void warmup(HelloService service, String payload, int seconds) {
        if (seconds == 0) {
            return;
        }
        System.out.println("Warmup " + seconds + "s...");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            service.hello(new Hello("warmup", payload));
        }
    }

    private static String payload(BenchmarkOptions options) {
        char[] chars = new char[options.getPayloadBytes()];
        if (options.getPayloadType() == BenchmarkOptions.PayloadType.REPEAT) {
            java.util.Arrays.fill(chars, 'F');
        } else {
            SplittableRandom random = new SplittableRandom(options.getSeed());
            for (int i = 0; i < chars.length; i++) {
                chars[i] = (char) random.nextInt(32, 127);
            }
        }
        return new String(chars);
    }

    private static ConnectionCounts connectionCounts(RpcRequestTransport transport) {
        if (transport instanceof NettyRpcClient) {
            NettyRpcClient client = (NettyRpcClient) transport;
            return new ConnectionCounts(
                    client.getCreatedConnectionCount(),
                    client.getReusedConnectionCount()
            );
        }
        if (transport instanceof SocketRpcClient) {
            SocketRpcClient client = (SocketRpcClient) transport;
            return new ConnectionCounts(
                    client.getCreatedConnectionCount(),
                    client.getReusedConnectionCount()
            );
        }
        return new ConnectionCounts(0L, 0L);
    }

    private static final class ConnectionCounts {
        private final long created;
        private final long reused;

        private ConnectionCounts(long created, long reused) {
            this.created = created;
            this.reused = reused;
        }
    }
}
