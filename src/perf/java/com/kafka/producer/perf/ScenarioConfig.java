package com.kafka.producer.perf;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class ScenarioConfig {
    private static final String DEFAULT_SCENARIO = "S-04";

    private final String scenario;
    private final int poolSize;
    private final int threads;
    private final Duration duration;
    private final int recordSizeBytes;
    private final int recordsPerTx;
    private final String bootstrapServers;
    private final String topic;
    private final long leaseTimeoutMs;
    private final Path resultsDir;

    private ScenarioConfig(String scenario, int poolSize, int threads, Duration duration,
                           int recordSizeBytes, int recordsPerTx, String bootstrapServers,
                           String topic, long leaseTimeoutMs, Path resultsDir) {
        this.scenario = scenario;
        this.poolSize = poolSize;
        this.threads = threads;
        this.duration = duration;
        this.recordSizeBytes = recordSizeBytes;
        this.recordsPerTx = recordsPerTx;
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
        this.leaseTimeoutMs = leaseTimeoutMs;
        this.resultsDir = resultsDir;
    }

    public static ScenarioConfig fromArgs(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2);
            String value = (i + 1) < args.length ? args[i + 1] : "";
            if (!value.startsWith("--")) {
                values.put(key, value);
                i++;
            } else {
                values.put(key, "true");
            }
        }

        String scenario = values.getOrDefault("scenario", System.getProperty("scenario", DEFAULT_SCENARIO));
        int poolSize = Integer.parseInt(values.getOrDefault("pool-size", System.getProperty("poolSize", "4")));
        int threads = Integer.parseInt(values.getOrDefault("threads", "4"));
        long durationSec = Long.parseLong(values.getOrDefault("duration-sec", "60"));
        int recordSize = Integer.parseInt(values.getOrDefault("record-size", "1024"));
        int recordsPerTx = Integer.parseInt(values.getOrDefault("records-per-tx", "10"));
        String bootstrapServers = values.getOrDefault("bootstrap-servers", "localhost:9092");
        String topic = values.getOrDefault("topic", "perf-test");
        long leaseTimeoutMs = Long.parseLong(values.getOrDefault("lease-timeout-ms", "5000"));
        Path resultsDir = Paths.get(values.getOrDefault("results-dir", "perf-results"));

        return new ScenarioConfig(
                scenario,
                poolSize,
                threads,
                Duration.ofSeconds(durationSec),
                recordSize,
                recordsPerTx,
                bootstrapServers,
                topic,
                leaseTimeoutMs,
                resultsDir);
    }

    public String scenario() { return scenario; }
    public int poolSize() { return poolSize; }
    public int threads() { return threads; }
    public Duration duration() { return duration; }
    public int recordSizeBytes() { return recordSizeBytes; }
    public int recordsPerTx() { return recordsPerTx; }
    public String bootstrapServers() { return bootstrapServers; }
    public String topic() { return topic; }
    public long leaseTimeoutMs() { return leaseTimeoutMs; }
    public Path resultsDir() { return resultsDir; }
}
