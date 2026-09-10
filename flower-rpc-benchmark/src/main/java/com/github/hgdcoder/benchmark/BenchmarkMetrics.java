package com.github.hgdcoder.benchmark;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 压测期间由多个工作线程共同更新的指标。
 *
 * <p>计数器遵守两个守恒关系：</p>
 * <pre>
 * planned = started + dropped
 * started = completed + inflight
 * </pre>
 */
final class BenchmarkMetrics {
    private final LongAdder planned = new LongAdder();
    private final LongAdder started = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final AtomicLong inflight = new AtomicLong();
    private final AtomicLong peakInflight = new AtomicLong();
    private final EnumMap<BenchmarkOutcome, LongAdder> outcomes =
            new EnumMap<>(BenchmarkOutcome.class);

    private final BoundedLatencyRecorder successLatency = new BoundedLatencyRecorder();
    private final BoundedLatencyRecorder terminalLatency = new BoundedLatencyRecorder();
    private final BoundedLatencyRecorder plannedToTerminalLatency = new BoundedLatencyRecorder();
    private final BoundedLatencyRecorder schedulingDelay = new BoundedLatencyRecorder();

    BenchmarkMetrics() {
        for (BenchmarkOutcome outcome : BenchmarkOutcome.values()) {
            outcomes.put(outcome, new LongAdder());
        }
    }

    void planned() {
        planned.increment();
    }

    void planned(long count) {
        planned.add(count);
    }

    void dropped() {
        dropped.increment();
    }

    void dropped(long count) {
        dropped.add(count);
    }

    void started(long schedulingDelayNanos) {
        started.increment();
        long current = inflight.incrementAndGet();
        peakInflight.accumulateAndGet(current, Math::max);
        if (schedulingDelayNanos >= 0) {
            schedulingDelay.recordNanos(schedulingDelayNanos);
        }
    }

    void completed(
            BenchmarkOutcome outcome,
            long invocationLatencyNanos,
            long plannedToTerminalNanos
    ) {
        outcomes.get(outcome).increment();
        completed.increment();
        inflight.decrementAndGet();
        terminalLatency.recordNanos(invocationLatencyNanos);
        plannedToTerminalLatency.recordNanos(plannedToTerminalNanos);
        if (outcome == BenchmarkOutcome.SUCCESS) {
            successLatency.recordNanos(invocationLatencyNanos);
        }
    }

    Snapshot snapshot() {
        EnumMap<BenchmarkOutcome, Long> counts = new EnumMap<>(BenchmarkOutcome.class);
        for (Map.Entry<BenchmarkOutcome, LongAdder> entry : outcomes.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().sum());
        }
        return new Snapshot(
                planned.sum(), started.sum(), dropped.sum(), completed.sum(),
                inflight.get(), peakInflight.get(), counts,
                successLatency.snapshot(), terminalLatency.snapshot(),
                plannedToTerminalLatency.snapshot(), schedulingDelay.snapshot()
        );
    }

    static final class Snapshot {
        private final long planned;
        private final long started;
        private final long dropped;
        private final long completed;
        private final long inflight;
        private final long peakInflight;
        private final EnumMap<BenchmarkOutcome, Long> outcomes;
        private final BoundedLatencyRecorder.Snapshot successLatency;
        private final BoundedLatencyRecorder.Snapshot terminalLatency;
        private final BoundedLatencyRecorder.Snapshot plannedToTerminalLatency;
        private final BoundedLatencyRecorder.Snapshot schedulingDelay;

        Snapshot(long planned, long started, long dropped, long completed,
                 long inflight, long peakInflight,
                 EnumMap<BenchmarkOutcome, Long> outcomes,
                 BoundedLatencyRecorder.Snapshot successLatency,
                 BoundedLatencyRecorder.Snapshot terminalLatency,
                 BoundedLatencyRecorder.Snapshot plannedToTerminalLatency,
                 BoundedLatencyRecorder.Snapshot schedulingDelay) {
            this.planned = planned;
            this.started = started;
            this.dropped = dropped;
            this.completed = completed;
            this.inflight = inflight;
            this.peakInflight = peakInflight;
            this.outcomes = outcomes;
            this.successLatency = successLatency;
            this.terminalLatency = terminalLatency;
            this.plannedToTerminalLatency = plannedToTerminalLatency;
            this.schedulingDelay = schedulingDelay;
        }

        boolean isConserved() {
            return planned == started + dropped
                    && started == completed + inflight;
        }

        long outcome(BenchmarkOutcome outcome) {
            return outcomes.get(outcome);
        }

        long failed() {
            return completed - outcome(BenchmarkOutcome.SUCCESS);
        }

        long getPlanned() { return planned; }
        long getStarted() { return started; }
        long getDropped() { return dropped; }
        long getCompleted() { return completed; }
        long getInflight() { return inflight; }
        long getPeakInflight() { return peakInflight; }
        BoundedLatencyRecorder.Snapshot getSuccessLatency() { return successLatency; }
        BoundedLatencyRecorder.Snapshot getTerminalLatency() { return terminalLatency; }
        BoundedLatencyRecorder.Snapshot getPlannedToTerminalLatency() { return plannedToTerminalLatency; }
        BoundedLatencyRecorder.Snapshot getSchedulingDelay() { return schedulingDelay; }
    }
}
