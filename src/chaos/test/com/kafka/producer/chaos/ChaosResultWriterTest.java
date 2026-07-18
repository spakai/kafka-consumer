package com.kafka.producer.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaosResultWriterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void writesSamplesEventsVerificationAndSummary() throws Exception {
        ChaosConfig config = ChaosConfig.fromArgs(new String[]{
                "--scenario", "MB-01",
                "--duration-sec", "1",
                "--results-dir", tempDirectory.toString()
        });
        ChaosLoadEngine.Sample sample = new ChaosLoadEngine.Sample(
                Instant.now(), 0, 2, 1, 1, 0,
                1, 2, 5, "HEALTHY", 7, 1, 8, 100, 0, 0);
        ChaosEvent event = ChaosEvent.of(
                "test", "passed", "quoted \"detail\"", Map.of("brokerId", 1));
        VerificationReport report = new VerificationReport(
                1, 1, 10, 0, 0, 0, 0, 0, List.of(),
                List.of(new VerificationReport.VerificationRow(
                        "publish-1", "COMMITTED", 10, 10, 1, "")));

        ChaosResultWriter.ResultFiles files = new ChaosResultWriter().write(
                config, "run-1", List.of(sample), List.of(event), report);

        assertTrue(Files.readString(files.samples()).contains("p95_latency_ms"));
        assertTrue(Files.readString(files.events()).contains("quoted \\\"detail\\\""));
        assertTrue(Files.readString(files.verification()).contains("publish-1"));
        assertTrue(Files.readString(tempDirectory.resolve("summary.md")).contains("PASS"));
    }
}
