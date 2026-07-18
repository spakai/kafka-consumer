package com.kafka.producer.baseline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class ResultWriter {
    private static final String COMPARISON_HEADER =
            "timestamp,scenario,run,implementation,topology,producer_count,threads,"
            + "records_per_transaction,record_size,records_per_second,transactions_per_second,"
            + "bytes_per_second,p50_ms,p95_ms,p99_ms,timeouts,failures,ambiguous,"
            + "heap_used_bytes,live_threads,correctness_passed,framework_version,kafka_client_version";

    public void write(BaselineConfig config, BenchmarkResult result) throws IOException {
        Files.createDirectories(config.resultsDir());
        String stamp = Instant.now().toString().replace(':', '-');
        Path raw = config.resultsDir().resolve(
                result.scenario() + "-" + result.implementation() + "-" + stamp + ".csv");
        StringBuilder samples = new StringBuilder("run_id,implementation,sample,transaction_latency_ms\n");
        int index = 0;
        for (long nanos : result.latencyNanos()) {
            samples.append(result.runId()).append(',').append(result.implementation()).append(',')
                    .append(index++).append(',').append(format(nanos / 1_000_000.0)).append('\n');
        }
        Files.writeString(raw, samples);

        Path comparison = config.resultsDir().resolve("comparison.csv");
        boolean addHeader = Files.notExists(comparison) || Files.size(comparison) == 0;
        String line = (addHeader ? COMPARISON_HEADER + "\n" : "") + comparisonRow(config, result);
        Files.writeString(comparison, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        appendSummary(config, result);
    }

    private static String comparisonRow(BaselineConfig config, BenchmarkResult result) {
        return String.join(",",
                Instant.now().toString(), result.scenario(), Integer.toString(config.runNumber()),
                result.implementation(), config.topology(),
                Integer.toString(config.producerCount()), Integer.toString(config.threads()),
                Integer.toString(config.recordsPerTransaction()),
                Integer.toString(config.recordSize()), format(result.recordsPerSecond()),
                format(result.transactionsPerSecond()), format(result.bytesPerSecond()),
                format(result.p50Ms()), format(result.p95Ms()), format(result.p99Ms()),
                Long.toString(result.timeouts()), Long.toString(result.failures()),
                Long.toString(result.ambiguousOutcomes()), Long.toString(result.heapUsedBytes()),
                Integer.toString(result.liveThreads()),
                Boolean.toString(result.correctness().passed()),
                result.implementation().equals("spring-kafka")
                        ? implementationVersion("org.springframework.kafka.core.KafkaTemplate")
                        : "core",
                implementationVersion("org.apache.kafka.clients.producer.KafkaProducer")) + "\n";
    }

    private static void appendSummary(BaselineConfig config, BenchmarkResult result)
            throws IOException {
        Path summary = config.resultsDir().resolve("summary.md");
        if (Files.notExists(summary)) {
            Files.writeString(summary, "# Spring Kafka transactional baseline\n\n"
                    + "Generated rows are observations from the recorded environment; "
                    + "invalid correctness runs must not be compared.\n");
        }
        String block = "\n## " + result.scenario() + " / " + result.implementation()
                + " / run " + config.runNumber() + "\n\n"
                + "- Environment: `" + config.topology() + "`, JVM `"
                + System.getProperty("java.version") + "`, topic `" + config.topic() + "`\n"
                + "- Configuration: producers " + config.producerCount() + ", callers "
                + config.threads() + ", records/transaction " + config.recordsPerTransaction()
                + ", record bytes " + config.recordSize() + ", seed " + config.seed() + "\n"
                + "- Throughput: " + format(result.recordsPerSecond())
                + " records/s; p95 transaction latency: " + format(result.p95Ms()) + " ms\n"
                + "- Failures: " + result.failures() + "; timeouts: " + result.timeouts()
                + "; ambiguous: " + result.ambiguousOutcomes() + "\n"
                + "- Correctness: " + (result.correctness().passed() ? "PASS" : "FAIL")
                + (result.correctness().issues().isEmpty() ? "" : " — "
                + String.join("; ", result.correctness().issues())) + "\n";
        Files.writeString(summary, block, StandardOpenOption.APPEND);
    }

    private static String implementationVersion(String className) {
        try {
            Package value = Class.forName(className).getPackage();
            return value.getImplementationVersion() == null ? "unknown"
                    : value.getImplementationVersion();
        } catch (ClassNotFoundException ignored) {
            return "unavailable";
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
