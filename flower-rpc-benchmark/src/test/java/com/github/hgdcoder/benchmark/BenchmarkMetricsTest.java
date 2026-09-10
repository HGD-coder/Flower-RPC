package com.github.hgdcoder.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkMetricsTest {
    @Test
    void keepsTerminalCountsMutuallyExclusiveAndConserved() {
        BenchmarkMetrics metrics = new BenchmarkMetrics();
        metrics.planned();
        metrics.started(10);
        metrics.completed(BenchmarkOutcome.SUCCESS, 1000, 1010);
        metrics.planned();
        metrics.dropped();

        BenchmarkMetrics.Snapshot snapshot = metrics.snapshot();
        assertTrue(snapshot.isConserved());
        assertEquals(2, snapshot.getPlanned());
        assertEquals(1, snapshot.getStarted());
        assertEquals(1, snapshot.getDropped());
        assertEquals(1, snapshot.getCompleted());
        assertEquals(1, snapshot.outcome(BenchmarkOutcome.SUCCESS));
        assertEquals(0, snapshot.failed());
    }
}
