package com.github.hgdcoder.benchmark;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedLatencyRecorderTest {
    @Test
    void reportsKnownPercentilesWithoutKeepingEverySample() {
        BoundedLatencyRecorder recorder = new BoundedLatencyRecorder();
        for (int millis = 1; millis <= 1000; millis++) {
            recorder.recordNanos(TimeUnit.MILLISECONDS.toNanos(millis));
        }

        BoundedLatencyRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(1000, snapshot.getCount());
        assertTrue(snapshot.getP50Ms() >= 499 && snapshot.getP50Ms() <= 501);
        assertTrue(snapshot.getP95Ms() >= 949 && snapshot.getP95Ms() <= 951);
        assertTrue(snapshot.getP99Ms() >= 989 && snapshot.getP99Ms() <= 991);
    }

    @Test
    void clampsAndCountsValuesOutsideConfiguredRange() {
        BoundedLatencyRecorder recorder = new BoundedLatencyRecorder();
        recorder.recordNanos(TimeUnit.MINUTES.toNanos(2));

        BoundedLatencyRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(1, snapshot.getOverflow());
        // 三位有效数字在量程末端允许几十毫秒的量化误差。
        assertEquals(60_000.0, snapshot.getMaxMs(), 50.0);
    }
}
