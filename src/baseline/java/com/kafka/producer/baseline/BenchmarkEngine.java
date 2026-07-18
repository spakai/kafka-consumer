package com.kafka.producer.baseline;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkEngine {
    public BenchmarkResult run(BaselineConfig config, ImplementationAdapter adapter)
            throws Exception {
        String runId = adapter.name() + "-" + config.scenario() + "-"
                + config.runNumber() + "-" + System.currentTimeMillis();
        AtomicLong sequences = new AtomicLong();
        runPhase(config, runId, adapter, config.warmup(), sequences, new PublishLedger(), false);

        PublishLedger ledger = new PublishLedger();
        long start = System.nanoTime();
        List<LoadWorker.Stats> workerStats =
                runPhase(config, runId, adapter, config.duration(), sequences, ledger, true);
        long durationNanos = System.nanoTime() - start;

        long transactions = workerStats.stream().mapToLong(value -> value.transactions).sum();
        long records = workerStats.stream().mapToLong(value -> value.records).sum();
        long timeouts = workerStats.stream().mapToLong(value -> value.timeouts).sum();
        long failures = workerStats.stream().mapToLong(value -> value.failures).sum();
        long ambiguous = workerStats.stream().mapToLong(value -> value.ambiguous).sum();
        List<Long> latencies = workerStats.stream()
                .flatMap(value -> value.latencies.stream()).sorted().toList();
        double seconds = durationNanos / 1_000_000_000.0;
        CorrectnessVerifier.Report correctness =
                new CorrectnessVerifier().verify(config, runId, ledger);
        Runtime runtime = Runtime.getRuntime();
        return new BenchmarkResult(
                config.scenario(), adapter.name(), runId,
                TimeUnit.NANOSECONDS.toMillis(durationNanos),
                transactions, records, timeouts, failures, ambiguous,
                records / seconds, transactions / seconds,
                records * (double) config.recordSize() / seconds,
                percentile(latencies, .50), percentile(latencies, .95),
                percentile(latencies, .99),
                runtime.totalMemory() - runtime.freeMemory(),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                latencies, correctness);
    }

    private static List<LoadWorker.Stats> runPhase(
            BaselineConfig config, String runId, ImplementationAdapter adapter,
            Duration duration, AtomicLong sequences, PublishLedger ledger, boolean measured)
            throws Exception {
        if (duration.isZero()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(config.threads());
        AtomicBoolean stop = new AtomicBoolean();
        List<Future<LoadWorker.Stats>> futures = new ArrayList<>();
        for (int worker = 0; worker < config.threads(); worker++) {
            futures.add(executor.submit(new LoadWorker(worker, config, runId, adapter,
                    stop, sequences, ledger, measured)));
        }
        Thread.sleep(duration.toMillis());
        stop.set(true);
        executor.shutdown();
        if (!executor.awaitTermination(2, TimeUnit.MINUTES)) {
            executor.shutdownNow();
            throw new IllegalStateException("workers did not stop within two minutes");
        }
        List<LoadWorker.Stats> result = new ArrayList<>();
        for (Future<LoadWorker.Stats> future : futures) {
            result.add(future.get());
        }
        return result;
    }

    static double percentile(List<Long> sortedNanos, double percentile) {
        if (sortedNanos.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sortedNanos.size()) - 1;
        return sortedNanos.get(Math.max(0, index)) / 1_000_000.0;
    }
}
