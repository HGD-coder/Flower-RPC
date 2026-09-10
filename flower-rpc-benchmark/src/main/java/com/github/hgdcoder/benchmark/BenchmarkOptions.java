package com.github.hgdcoder.benchmark;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一次压测场景的不可变参数。
 *
 * <p>所有入口都先把命令行转换成这个对象，后面的运行器不再直接解析字符串，
 * 这样单场与批量压测会共享完全相同的参数校验。</p>
 */
final class BenchmarkOptions {
    enum Mode {
        CONCURRENCY,
        RATE;

        static Mode parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "--mode must be concurrency or rate: " + value,
                        e
                );
            }
        }
    }

    enum PayloadType {
        REPEAT,
        DETERMINISTIC_RANDOM;

        static PayloadType parse(String value) {
            String normalized = value.trim()
                    .replace('-', '_')
                    .toUpperCase(Locale.ROOT);
            try {
                return valueOf(normalized);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "--payload-type must be repeat or deterministic-random: " + value,
                        e
                );
            }
        }
    }

    private static final Set<String> SUPPORTED_KEYS = new HashSet<>(Arrays.asList(
            "mode", "threads", "rate", "max-inflight", "duration", "warmup",
            "drain-timeout", "payload-bytes", "payload-type", "seed", "output",
            "payloads", "threads-list", "rates", "repeats"
    ));

    private final Mode mode;
    private final int threads;
    private final long rate;
    private final int maxInflight;
    private final int durationSeconds;
    private final int warmupSeconds;
    private final int drainTimeoutSeconds;
    private final int payloadBytes;
    private final PayloadType payloadType;
    private final long seed;
    private final Path output;
    private final int repeat;

    private BenchmarkOptions(
            Mode mode,
            int threads,
            long rate,
            int maxInflight,
            int durationSeconds,
            int warmupSeconds,
            int drainTimeoutSeconds,
            int payloadBytes,
            PayloadType payloadType,
            long seed,
            Path output,
            int repeat
    ) {
        this.mode = mode;
        this.threads = requirePositive("--threads", threads);
        this.rate = requirePositive("--rate", rate);
        this.maxInflight = requirePositive("--max-inflight", maxInflight);
        this.durationSeconds = requirePositive("--duration", durationSeconds);
        this.warmupSeconds = requireNonNegative("--warmup", warmupSeconds);
        this.drainTimeoutSeconds = requirePositive("--drain-timeout", drainTimeoutSeconds);
        this.payloadBytes = requireNonNegative("--payload-bytes", payloadBytes);
        this.payloadType = payloadType;
        this.seed = seed;
        this.output = output;
        this.repeat = requirePositive("repeat", repeat);
    }

    static BenchmarkOptions parse(String[] args) {
        Map<String, String> values = parseArguments(args);
        return new BenchmarkOptions(
                Mode.parse(values.getOrDefault("mode", "concurrency")),
                intValue(values, "threads", 4),
                longValue(values, "rate", 1000L),
                intValue(values, "max-inflight", 1024),
                intValue(values, "duration", 30),
                intValue(values, "warmup", 5),
                intValue(values, "drain-timeout", 10),
                intValue(values, "payload-bytes", 9),
                PayloadType.parse(values.getOrDefault("payload-type", "repeat")),
                longValue(values, "seed", 20260909L),
                Paths.get(values.getOrDefault("output", "benchmark-results.csv")),
                1
        );
    }

    static int[] intList(String[] args, String key, int[] defaults) {
        String raw = parseArguments(args).get(key);
        if (raw == null) {
            return defaults.clone();
        }
        String[] parts = raw.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = requirePositive("--" + key, Integer.parseInt(parts[i].trim()));
        }
        return result;
    }

    static long[] longList(String[] args, String key, long[] defaults) {
        String raw = parseArguments(args).get(key);
        if (raw == null) {
            return defaults.clone();
        }
        String[] parts = raw.split(",");
        long[] result = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = requirePositive("--" + key, Long.parseLong(parts[i].trim()));
        }
        return result;
    }

    static int repeats(String[] args) {
        return requirePositive("--repeats", intValue(parseArguments(args), "repeats", 1));
    }

    BenchmarkOptions scenario(int newPayloadBytes, int newThreads, long newRate, int newRepeat) {
        return new BenchmarkOptions(
                mode, newThreads, newRate, maxInflight, durationSeconds, warmupSeconds,
                drainTimeoutSeconds, newPayloadBytes, payloadType, seed, output, newRepeat
        );
    }

    private static Map<String, String> parseArguments(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (String argument : args) {
            if (!argument.startsWith("--") || !argument.contains("=")) {
                throw new IllegalArgumentException(
                        "argument must use --name=value format: " + argument
                );
            }
            int separator = argument.indexOf('=');
            String key = argument.substring(2, separator);
            if (!SUPPORTED_KEYS.contains(key)) {
                throw new IllegalArgumentException("unsupported argument: --" + key);
            }
            values.put(key, argument.substring(separator + 1));
        }
        return values;
    }

    private static int intValue(Map<String, String> values, String key, int defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static long longValue(Map<String, String> values, String key, long defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : Long.parseLong(value);
    }

    private static int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    Mode getMode() { return mode; }
    int getThreads() { return threads; }
    long getRate() { return rate; }
    int getMaxInflight() { return maxInflight; }
    int getDurationSeconds() { return durationSeconds; }
    int getWarmupSeconds() { return warmupSeconds; }
    int getDrainTimeoutSeconds() { return drainTimeoutSeconds; }
    int getPayloadBytes() { return payloadBytes; }
    PayloadType getPayloadType() { return payloadType; }
    long getSeed() { return seed; }
    Path getOutput() { return output; }
    int getRepeat() { return repeat; }
}
