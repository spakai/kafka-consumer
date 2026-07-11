package com.kafka.producer.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

public final class ResultWriter {
    private ResultWriter() {}

    public static Path writeCsv(Path dir, String scenario, List<String> lines) throws IOException {
        Files.createDirectories(dir);
        Path output = dir.resolve(scenario + "-" + LoadWorker.timestamp() + ".csv");
        Files.write(output, lines);
        return output;
    }

    public static void appendSummary(Path dir, String scenario, List<String> summaryLines) throws IOException {
        Files.createDirectories(dir);
        Path summary = dir.resolve("summary.md");
        StringBuilder sb = new StringBuilder();
        sb.append("\n## ").append(scenario).append(" (generated ").append(Instant.now()).append(")\n");
        for (String line : summaryLines) {
            sb.append(line).append("\n");
        }
        Files.writeString(summary, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
