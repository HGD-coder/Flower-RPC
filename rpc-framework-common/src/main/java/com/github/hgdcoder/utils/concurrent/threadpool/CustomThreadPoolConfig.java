package com.github.hgdcoder.utils.concurrent.threadpool;

import java.util.concurrent.TimeUnit;

/**
 * 一份不可变的线程池配置。
 *
 * <p>这里只保存队列容量，不直接保存 BlockingQueue。这样同一份配置创建多个
 * 线程池时，每个线程池都会拥有自己的任务队列，不会错误地共用队列。</p>
 */
public final class CustomThreadPoolConfig {
    private final int corePoolSize;
    private final int maximumPoolSize;
    private final long keepAliveTime;
    private final TimeUnit timeUnit;
    private final int queueCapacity;

    private CustomThreadPoolConfig(Builder builder) {
        if (builder.corePoolSize <= 0) {
            throw new IllegalArgumentException("corePoolSize must be positive");
        }
        if (builder.maximumPoolSize < builder.corePoolSize) {
            throw new IllegalArgumentException(
                    "maximumPoolSize must be greater than or equal to corePoolSize"
            );
        }
        if (builder.keepAliveTime < 0) {
            throw new IllegalArgumentException("keepAliveTime must not be negative");
        }
        if (builder.timeUnit == null) {
            throw new IllegalArgumentException("timeUnit must not be null");
        }
        if (builder.queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }

        this.corePoolSize = builder.corePoolSize;
        this.maximumPoolSize = builder.maximumPoolSize;
        this.keepAliveTime = builder.keepAliveTime;
        this.timeUnit = builder.timeUnit;
        this.queueCapacity = builder.queueCapacity;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public long getKeepAliveTime() {
        return keepAliveTime;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    /** 带有保守默认值的配置建造器。 */
    public static final class Builder {
        private int corePoolSize = 4;
        private int maximumPoolSize = 8;
        private long keepAliveTime = 60L;
        private TimeUnit timeUnit = TimeUnit.SECONDS;
        private int queueCapacity = 256;

        private Builder() {
        }

        public Builder corePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
            return this;
        }

        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        public Builder keepAliveTime(long keepAliveTime) {
            this.keepAliveTime = keepAliveTime;
            return this;
        }

        public Builder timeUnit(TimeUnit timeUnit) {
            this.timeUnit = timeUnit;
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public CustomThreadPoolConfig build() {
            return new CustomThreadPoolConfig(this);
        }
    }
}
