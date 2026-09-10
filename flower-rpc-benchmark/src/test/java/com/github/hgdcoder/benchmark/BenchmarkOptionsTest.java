package com.github.hgdcoder.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkOptionsTest {
    @Test
    void parsesRateScenario() {
        BenchmarkOptions options = BenchmarkOptions.parse(new String[]{
                "--mode=rate",
                "--rate=5000",
                "--max-inflight=128",
                "--payload-type=deterministic-random",
                "--seed=7"
        });

        assertEquals(BenchmarkOptions.Mode.RATE, options.getMode());
        assertEquals(5000L, options.getRate());
        assertEquals(128, options.getMaxInflight());
        assertEquals(BenchmarkOptions.PayloadType.DETERMINISTIC_RANDOM,
                options.getPayloadType());
        assertEquals(7L, options.getSeed());
    }

    @Test
    void rejectsUnknownAndInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[]{"--thread=4"}));
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[]{"--threads=0"}));
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[]{"threads=4"}));
    }

    @Test
    void parsesBatchLists() {
        String[] args = {"--payloads=9,1024", "--threads-list=1,8", "--rates=100,200"};
        assertEquals(2, BenchmarkOptions.intList(args, "payloads", new int[]{1}).length);
        assertEquals(8, BenchmarkOptions.intList(args, "threads-list", new int[]{1})[1]);
        assertEquals(200L, BenchmarkOptions.longList(args, "rates", new long[]{1})[1]);
    }
}
