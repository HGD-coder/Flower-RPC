package com.github.hgdcoder.benchmark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** 把每个已完成场次立即追加到 CSV，进程中断时不会丢失之前的结果。 */
final class BenchmarkCsvWriter {
    private BenchmarkCsvWriter() {
    }

    static synchronized void append(Path output, BenchmarkResult result) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean writeHeader = !Files.exists(absolute) || Files.size(absolute) == 0;
        try (BufferedWriter writer = Files.newBufferedWriter(
                absolute,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            if (writeHeader) {
                writer.write(line(BenchmarkResult.CSV_HEADER));
                writer.newLine();
            }
            writer.write(line(result.csvValues()));
            writer.newLine();
        }
    }

    static String line(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(escape(values.get(i)));
        }
        return result.toString();
    }

    static String escape(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
