package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.config.RpcFrameworkConfig;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 一场压测结束后的不可变结果，同时负责生成控制台摘要和 CSV 列。 */
final class BenchmarkResult {
    static final List<String> CSV_HEADER = List.of(
            "timestamp", "commit", "java", "os", "processors", "maxHeapBytes",
            "mode", "threads", "rate", "maxInflight", "durationSeconds", "repeat",
            "payloadBytes", "payloadType", "seed",
            "transport", "discovery", "loadBalance", "serializer", "compress",
            "connectTimeoutMillis", "requestTimeoutMillis",
            "planned", "started", "dropped", "completed", "inflight", "peakInflight",
            "success", "failed", "cancelled", "deadlineExceeded", "resourceExhausted",
            "unavailable", "otherRpcError", "clientError", "successQps",
            "successAvgMs", "successP50Ms", "successP95Ms", "successP99Ms", "successMaxMs",
            "terminalP99Ms", "plannedToTerminalP99Ms", "schedulingDelayP99Ms",
            "successLatencyOverflow", "terminalLatencyOverflow",
            "plannedLatencyOverflow", "schedulingDelayOverflow",
            "processCpuMillis", "averageProcessCpuCores", "heapBeforeBytes",
            "heapAfterBytes", "heapDeltaBytes", "gcCount", "gcTimeMillis",
            "createdConnections", "reusedConnections", "reuseRate",
            "conserved"
    );

    private final String timestamp = OffsetDateTime.now().toString();
    private final String commit = detectCommit();
    private final String javaVersion = System.getProperty("java.version", "unknown");
    private final String os = System.getProperty("os.name", "unknown") + " "
            + System.getProperty("os.arch", "unknown");
    private final int processors = Runtime.getRuntime().availableProcessors();
    private final long maxHeapBytes = Runtime.getRuntime().maxMemory();
    private final BenchmarkOptions options;
    private final RpcFrameworkConfig config;
    private final BenchmarkMetrics.Snapshot metrics;
    private final double elapsedSeconds;
    private final long createdConnections;
    private final long reusedConnections;
    private final BenchmarkRuntimeSnapshot runtimeBefore;
    private final BenchmarkRuntimeSnapshot runtimeAfter;

    BenchmarkResult(BenchmarkOptions options, RpcFrameworkConfig config,
                    BenchmarkMetrics.Snapshot metrics, long elapsedNanos,
                    long createdConnections, long reusedConnections,
                    BenchmarkRuntimeSnapshot runtimeBefore,
                    BenchmarkRuntimeSnapshot runtimeAfter) {
        this.options = options;
        this.config = config;
        this.metrics = metrics;
        this.elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        this.createdConnections = createdConnections;
        this.reusedConnections = reusedConnections;
        this.runtimeBefore = runtimeBefore;
        this.runtimeAfter = runtimeAfter;
    }

    void print() {
        BoundedLatencyRecorder.Snapshot success = metrics.getSuccessLatency();
        System.out.println("success=" + metrics.outcome(BenchmarkOutcome.SUCCESS));
        System.out.println("failed=" + metrics.failed());
        System.out.println("dropped=" + metrics.getDropped());
        System.out.println("qps=" + format(successQps()));
        System.out.println("avgMs=" + format(success.getAvgMs()));
        System.out.println("p50Ms=" + format(success.getP50Ms()));
        System.out.println("p95Ms=" + format(success.getP95Ms()));
        System.out.println("p99Ms=" + format(success.getP99Ms()));
        System.out.println("maxMs=" + format(success.getMaxMs()));
        System.out.println("terminalP99Ms="
                + format(metrics.getTerminalLatency().getP99Ms()));
        System.out.println("plannedToTerminalP99Ms="
                + format(metrics.getPlannedToTerminalLatency().getP99Ms()));
        System.out.println("schedulingDelayP99Ms="
                + format(metrics.getSchedulingDelay().getP99Ms()));
        System.out.println("planned=" + metrics.getPlanned()
                + ", started=" + metrics.getStarted()
                + ", completed=" + metrics.getCompleted()
                + ", inflight=" + metrics.getInflight()
                + ", peakInflight=" + metrics.getPeakInflight()
                + ", conserved=" + metrics.isConserved());
        System.out.println("cancelled=" + metrics.outcome(BenchmarkOutcome.CANCELLED)
                + ", deadlineExceeded=" + metrics.outcome(BenchmarkOutcome.DEADLINE_EXCEEDED)
                + ", resourceExhausted=" + metrics.outcome(BenchmarkOutcome.RESOURCE_EXHAUSTED)
                + ", unavailable=" + metrics.outcome(BenchmarkOutcome.UNAVAILABLE)
                + ", otherRpcError=" + metrics.outcome(BenchmarkOutcome.OTHER_RPC_ERROR)
                + ", clientError=" + metrics.outcome(BenchmarkOutcome.CLIENT_ERROR));
        System.out.println("createdConnections=" + createdConnections);
        System.out.println("reusedConnections=" + reusedConnections);
        System.out.println("connectionReuseRate=" + format(reuseRate()) + "%");
        System.out.println("successLatencyOverflow=" + success.getOverflow());
        System.out.println("terminalLatencyOverflow="
                + metrics.getTerminalLatency().getOverflow());
        System.out.println("plannedLatencyOverflow="
                + metrics.getPlannedToTerminalLatency().getOverflow());
        System.out.println("schedulingDelayOverflow="
                + metrics.getSchedulingDelay().getOverflow());
        System.out.println("processCpuMillis=" + format(processCpuMillis()));
        System.out.println("averageProcessCpuCores=" + format(averageProcessCpuCores()));
        System.out.println("heapDeltaBytes=" + runtimeAfter.heapDeltaBytes(runtimeBefore));
        System.out.println("gcCount=" + runtimeAfter.gcCountDelta(runtimeBefore));
        System.out.println("gcTimeMillis=" + runtimeAfter.gcTimeDeltaMillis(runtimeBefore));
    }

    List<String> csvValues() {
        BoundedLatencyRecorder.Snapshot success = metrics.getSuccessLatency();
        List<String> values = new ArrayList<>();
        add(values, timestamp, commit, javaVersion, os, processors, maxHeapBytes,
                options.getMode().name().toLowerCase(Locale.ROOT), options.getThreads(),
                options.getRate(), options.getMaxInflight(), options.getDurationSeconds(),
                options.getRepeat(), options.getPayloadBytes(),
                options.getPayloadType().name().toLowerCase(Locale.ROOT), options.getSeed(),
                config.getTransport(), config.getDiscovery(), config.getLoadBalance(),
                config.getSerializer(), config.getCompress(), config.getConnectTimeoutMillis(),
                config.getRequestTimeoutMillis(), metrics.getPlanned(), metrics.getStarted(),
                metrics.getDropped(), metrics.getCompleted(), metrics.getInflight(),
                metrics.getPeakInflight(), metrics.outcome(BenchmarkOutcome.SUCCESS),
                metrics.failed(), metrics.outcome(BenchmarkOutcome.CANCELLED),
                metrics.outcome(BenchmarkOutcome.DEADLINE_EXCEEDED),
                metrics.outcome(BenchmarkOutcome.RESOURCE_EXHAUSTED),
                metrics.outcome(BenchmarkOutcome.UNAVAILABLE),
                metrics.outcome(BenchmarkOutcome.OTHER_RPC_ERROR),
                metrics.outcome(BenchmarkOutcome.CLIENT_ERROR), format(successQps()),
                format(success.getAvgMs()), format(success.getP50Ms()),
                format(success.getP95Ms()), format(success.getP99Ms()),
                format(success.getMaxMs()), format(metrics.getTerminalLatency().getP99Ms()),
                format(metrics.getPlannedToTerminalLatency().getP99Ms()),
                format(metrics.getSchedulingDelay().getP99Ms()), success.getOverflow(),
                metrics.getTerminalLatency().getOverflow(),
                metrics.getPlannedToTerminalLatency().getOverflow(),
                metrics.getSchedulingDelay().getOverflow(),
                format(processCpuMillis()), format(averageProcessCpuCores()),
                runtimeBefore.getHeapUsedBytes(), runtimeAfter.getHeapUsedBytes(),
                runtimeAfter.heapDeltaBytes(runtimeBefore),
                runtimeAfter.gcCountDelta(runtimeBefore),
                runtimeAfter.gcTimeDeltaMillis(runtimeBefore),
                createdConnections, reusedConnections, format(reuseRate()),
                metrics.isConserved());
        return values;
    }

    private double successQps() {
        return elapsedSeconds == 0.0
                ? 0.0
                : metrics.outcome(BenchmarkOutcome.SUCCESS) / elapsedSeconds;
    }

    private double reuseRate() {
        long total = createdConnections + reusedConnections;
        return total == 0 ? 0.0 : reusedConnections * 100.0 / total;
    }

    private double processCpuMillis() {
        long cpuNanos = runtimeAfter.cpuDeltaNanos(runtimeBefore);
        return cpuNanos < 0 ? -1.0 : cpuNanos / 1_000_000.0;
    }

    /** 测量期间该 JVM 平均占用了多少个逻辑 CPU 核。 */
    private double averageProcessCpuCores() {
        long cpuNanos = runtimeAfter.cpuDeltaNanos(runtimeBefore);
        return cpuNanos < 0 || elapsedSeconds == 0.0
                ? -1.0
                : cpuNanos / (elapsedSeconds * 1_000_000_000.0);
    }

    private static void add(List<String> target, Object... values) {
        for (Object value : values) {
            target.add(String.valueOf(value));
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String detectCommit() {
        String configured = System.getProperty("flower.benchmark.commit");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    || process.exitValue() != 0) {
                process.destroyForcibly();
                return "unknown";
            }
            return new String(process.getInputStream().readAllBytes()).trim();
        } catch (Exception ignored) {
            return "unknown";
        }
    }
}
