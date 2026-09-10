package com.github.hgdcoder.benchmark;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

/** 测量边界上的客户端 JVM 资源快照，用差值辅助解释压测结果。 */
final class BenchmarkRuntimeSnapshot {
    private final long processCpuNanos;
    private final long heapUsedBytes;
    private final long gcCount;
    private final long gcTimeMillis;

    private BenchmarkRuntimeSnapshot(
            long processCpuNanos,
            long heapUsedBytes,
            long gcCount,
            long gcTimeMillis
    ) {
        this.processCpuNanos = processCpuNanos;
        this.heapUsedBytes = heapUsedBytes;
        this.gcCount = gcCount;
        this.gcTimeMillis = gcTimeMillis;
    }

    static BenchmarkRuntimeSnapshot capture() {
        long cpuNanos = -1L;
        java.lang.management.OperatingSystemMXBean operatingSystem =
                ManagementFactory.getOperatingSystemMXBean();
        if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean) {
            cpuNanos = ((com.sun.management.OperatingSystemMXBean) operatingSystem)
                    .getProcessCpuTime();
        }

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long collections = 0L;
        long collectionMillis = 0L;
        for (GarbageCollectorMXBean collector
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getCollectionCount() >= 0) {
                collections += collector.getCollectionCount();
            }
            if (collector.getCollectionTime() >= 0) {
                collectionMillis += collector.getCollectionTime();
            }
        }
        return new BenchmarkRuntimeSnapshot(
                cpuNanos,
                memory.getHeapMemoryUsage().getUsed(),
                collections,
                collectionMillis
        );
    }

    long cpuDeltaNanos(BenchmarkRuntimeSnapshot before) {
        if (processCpuNanos < 0 || before.processCpuNanos < 0) {
            return -1L;
        }
        return Math.max(0L, processCpuNanos - before.processCpuNanos);
    }

    long heapDeltaBytes(BenchmarkRuntimeSnapshot before) {
        return heapUsedBytes - before.heapUsedBytes;
    }

    long gcCountDelta(BenchmarkRuntimeSnapshot before) {
        return Math.max(0L, gcCount - before.gcCount);
    }

    long gcTimeDeltaMillis(BenchmarkRuntimeSnapshot before) {
        return Math.max(0L, gcTimeMillis - before.gcTimeMillis);
    }

    long getHeapUsedBytes() {
        return heapUsedBytes;
    }
}
