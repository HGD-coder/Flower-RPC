package com.github.hgdcoder.benchmark;

import com.github.hgdcoder.config.RpcFrameworkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkCsvWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesHeaderOnceAndAppendsCompletedScenarios() throws Exception {
        BenchmarkOptions options = BenchmarkOptions.parse(new String[]{"--duration=1"});
        BenchmarkMetrics metrics = new BenchmarkMetrics();
        metrics.planned();
        metrics.started(0);
        metrics.completed(BenchmarkOutcome.SUCCESS, 1000, 1000);
        BenchmarkResult result = new BenchmarkResult(
                options,
                RpcFrameworkConfig.defaults(),
                metrics.snapshot(),
                1_000_000_000L,
                1,
                1,
                BenchmarkRuntimeSnapshot.capture(),
                BenchmarkRuntimeSnapshot.capture()
        );
        Path output = temporaryDirectory.resolve("result.csv");

        BenchmarkCsvWriter.append(output, result);
        BenchmarkCsvWriter.append(output, result);

        List<String> lines = Files.readAllLines(output);
        assertEquals(3, lines.size());
        assertEquals(BenchmarkCsvWriter.line(BenchmarkResult.CSV_HEADER), lines.get(0));
        assertEquals(BenchmarkResult.CSV_HEADER.size(), result.csvValues().size());
    }

    @Test
    void escapesCommaQuoteAndLineBreak() {
        assertEquals("plain", BenchmarkCsvWriter.escape("plain"));
        assertEquals("\"a,b\"", BenchmarkCsvWriter.escape("a,b"));
        assertEquals("\"a\"\"b\"", BenchmarkCsvWriter.escape("a\"b"));
        assertEquals("\"a\nb\"", BenchmarkCsvWriter.escape("a\nb"));
    }
}
