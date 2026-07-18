package com.kafka.producer.baseline;

import java.util.List;

public record BenchmarkResult(
        String scenario,
        String implementation,
        String runId,
        long durationMs,
        long successfulTransactions,
        long successfulRecords,
        long timeouts,
        long failures,
        long ambiguousOutcomes,
        double recordsPerSecond,
        double transactionsPerSecond,
        double bytesPerSecond,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        long heapUsedBytes,
        int liveThreads,
        List<Long> latencyNanos,
        CorrectnessVerifier.Report correctness) {
}
