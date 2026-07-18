package com.kafka.producer.baseline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record BaselineConfig(
        String scenario,
        Set<String> implementations,
        String topology,
        String bootstrapServers,
        String topic,
        int producerCount,
        int threads,
        int recordsPerTransaction,
        int recordSize,
        Duration duration,
        Duration warmup,
        long acquisitionTimeoutMs,
        long seed,
        String compression,
        Path resultsDir,
        int runNumber) {

    public static BaselineConfig fromSystemProperties() {
        return from(Map.of());
    }

    public static BaselineConfig fromArgs(String[] args) {
        var values = Arrays.stream(args)
                .filter(value -> value.startsWith("--") && value.contains("="))
                .map(value -> value.substring(2).split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));
        return from(values);
    }

    static BaselineConfig from(Map<String, String> args) {
        String scenario = value(args, "scenario", "B-01");
        String topology = value(args, "topology", "single-broker");
        int producers = integer(args, "producerCount", 4);
        int defaultThreads = scenario.equals("B-04") ? 8 : producers;
        return new BaselineConfig(
                scenario,
                new LinkedHashSet<>(Arrays.asList(
                        value(args, "implementation", "pool,spring-kafka").split(","))),
                topology,
                value(args, "bootstrapServers",
                        topology.equals("three-broker") ? "localhost:19092,localhost:29092,localhost:39092"
                                : "localhost:9092"),
                value(args, "topic", topology.equals("three-broker")
                        ? "baseline-compare-replicated" : "baseline-compare-single"),
                producers,
                integer(args, "threads", defaultThreads),
                integer(args, "recordsPerTransaction", scenario.equals("B-01") ? 1 : 10),
                integer(args, "recordSize", 1024),
                Duration.ofSeconds(longValue(args, "durationSec", scenario.equals("B-05") ? 600 : 60)),
                Duration.ofSeconds(longValue(args, "warmupSec", 10)),
                longValue(args, "acquisitionTimeoutMs", 5_000),
                longValue(args, "seed", 7_193),
                value(args, "compression", "none"),
                Path.of(value(args, "resultsDir", "baseline-results")),
                integer(args, "runNumber", 1));
    }

    private static String value(Map<String, String> args, String key, String fallback) {
        return args.getOrDefault(key, System.getProperty(key, fallback));
    }

    private static int integer(Map<String, String> args, String key, int fallback) {
        return Integer.parseInt(value(args, key, Integer.toString(fallback)));
    }

    private static long longValue(Map<String, String> args, String key, long fallback) {
        return Long.parseLong(value(args, key, Long.toString(fallback)));
    }
}
