package com.github.hgdcoder.benchmark;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 使用固定量程的 HdrHistogram 记录延迟，内存不会随请求数量增长。
 */
final class BoundedLatencyRecorder {
    static final long HIGHEST_TRACKABLE_MICROS = TimeUnit.SECONDS.toMicros(60);

    private final Recorder recorder = new Recorder(HIGHEST_TRACKABLE_MICROS, 3);
    private final LongAdder overflow = new LongAdder();

    void recordNanos(long nanoseconds) {
        long micros = Math.max(1L, TimeUnit.NANOSECONDS.toMicros(Math.max(0L, nanoseconds)));
        if (micros > HIGHEST_TRACKABLE_MICROS) {
            overflow.increment();
            micros = HIGHEST_TRACKABLE_MICROS;
        }
        recorder.recordValue(micros);
    }

    Snapshot snapshot() {
        Histogram histogram = recorder.getIntervalHistogram();
        return new Snapshot(
                histogram.getTotalCount(),
                histogram.getMean() / 1000.0,
                histogram.getValueAtPercentile(50.0) / 1000.0,
                histogram.getValueAtPercentile(95.0) / 1000.0,
                histogram.getValueAtPercentile(99.0) / 1000.0,
                histogram.getMaxValue() / 1000.0,
                overflow.sum()
        );
    }

    static final class Snapshot {
        private final long count;
        private final double avgMs;
        private final double p50Ms;
        private final double p95Ms;
        private final double p99Ms;
        private final double maxMs;
        private final long overflow;

        Snapshot(long count, double avgMs, double p50Ms, double p95Ms,
                 double p99Ms, double maxMs, long overflow) {
            this.count = count;
            this.avgMs = avgMs;
            this.p50Ms = p50Ms;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
            this.maxMs = maxMs;
            this.overflow = overflow;
        }

        long getCount() { return count; }
        double getAvgMs() { return avgMs; }
        double getP50Ms() { return p50Ms; }
        double getP95Ms() { return p95Ms; }
        double getP99Ms() { return p99Ms; }
        double getMaxMs() { return maxMs; }
        long getOverflow() { return overflow; }
    }
}
