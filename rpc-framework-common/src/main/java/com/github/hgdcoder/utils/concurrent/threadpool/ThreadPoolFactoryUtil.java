package com.github.hgdcoder.utils.concurrent.threadpool;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 统一创建、命名和关闭 Flower-RPC 自己管理的线程池。 */
public final class ThreadPoolFactoryUtil {

    private ThreadPoolFactoryUtil() {
    }

    public static ThreadPoolExecutor createThreadPool(
            CustomThreadPoolConfig config,
            String threadNamePrefix,
            boolean daemon,
            RejectedExecutionHandler rejectedHandler
    ){
        return createThreadPool(
                config,
                createThreadFactory(threadNamePrefix, daemon),
                rejectedHandler
        );
    }

    /**
     * 使用有界 ArrayBlockingQueue 创建线程池。
     * 队列满且线程数已到上限时，由 rejectedHandler 明确决定如何背压。
     */
    public static ThreadPoolExecutor createThreadPool(
            CustomThreadPoolConfig config,
            ThreadFactory threadFactory,
            RejectedExecutionHandler rejectedHandler
    ) {
        if(config == null || threadFactory == null || rejectedHandler == null) {
            throw new IllegalArgumentException(
                    "config, threadFactory and rejectedHandler must not be null"
            );
        }
        return new ThreadPoolExecutor(
                config.getCorePoolSize(),
                config.getMaximumPoolSize(),
                config.getKeepAliveTime(),
                config.getTimeUnit(),
                new ArrayBlockingQueue<>(config.getQueueCapacity()),
                threadFactory,
                rejectedHandler
        );
    }

    /** 创建名称稳定、便于日志和线程转储定位的线程。 */
    public static ThreadFactory createThreadFactory(
            String threadNamePrefix,
            boolean daemon
    ) {
        if(threadNamePrefix == null || threadNamePrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }
        ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        AtomicInteger sequence = new AtomicInteger();
        return task-> {
            Thread thread = defaultFactory.newThread(task);
            thread.setName(threadNamePrefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(daemon);
            return thread;
        };
    }


    /**
     * 先拒绝新任务并等待已有任务结束；超时后中断剩余任务。
     */
    public static void shutdownGracefully(
            ExecutorService executor,
            long timeout,
            TimeUnit unit
    ) {
        if(executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
