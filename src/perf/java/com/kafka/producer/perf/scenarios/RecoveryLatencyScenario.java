package com.kafka.producer.perf.scenarios;

import com.kafka.producer.perf.LatencyRecorder;
import com.kafka.producer.perf.LoadWorker;
import com.kafka.producer.perf.ResultWriter;
import com.kafka.producer.perf.ScenarioConfig;
import com.kafka.producer.pool.PoolConfig;
import com.kafka.producer.pool.ProducerLease;
import com.kafka.producer.pool.PoolState;
import com.kafka.producer.pool.TransactionalProducerPool;
import org.apache.kafka.clients.producer.KafkaProducer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecoveryLatencyScenario {

    public void run(ScenarioConfig config) throws Exception {
        TransactionalProducerPool pool = ScenarioSupport.createPool(config, 4);
        LatencyRecorder txLatency = new LatencyRecorder();
        LatencyRecorder leaseLatency = new LatencyRecorder();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        AtomicBoolean stop = new AtomicBoolean(false);
        List<Future<LoadWorker.Stats>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(executor.submit(new LoadWorker(
                    stop,
                    ScenarioSupport.transactionalOperation(pool, config.topic(), ScenarioSupport.payload(1024), 10, leaseLatency),
                    txLatency)));
        }

        Thread.sleep(3000);
        long fenceStart = System.nanoTime();
        injectFence(config);

        long recoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fenceStart);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && pool.getPoolState() != PoolState.HEALTHY) {
            Thread.sleep(100);
            recoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fenceStart);
        }

        stop.set(true);
        executor.shutdown();
        executor.awaitTermination(15, TimeUnit.SECONDS);

        long failures = 0;
        for (Future<LoadWorker.Stats> future : futures) {
            failures += future.get().failures;
        }

        pool.shutdown();

        List<String> csv = List.of(
                "scenario,recovery_latency_ms,pool_state,tx_failures,p95_tx_ms",
                String.format("S-07,%d,%s,%d,%.3f", recoveryMs, pool.getPoolState(), failures, txLatency.p95Millis())
        );
        Path csvFile = ResultWriter.writeCsv(config.resultsDir(), "S-07", csv);
        ResultWriter.appendSummary(config.resultsDir(), "S-07", List.of(
                "- recovery latency (ms): " + recoveryMs,
                "- final pool state: " + pool.getPoolState(),
                "- transactions lost (failures): " + failures,
                "- CSV: `" + csvFile.getFileName() + "`"));
    }

    private void injectFence(ScenarioConfig config) {
        PoolConfig poolConfig = ScenarioSupport.poolConfig(config, 4);
        String transactionalId = poolConfig.buildTransactionalId(0);

        Properties props = ScenarioSupport.kafkaProps(config);
        props.setProperty("transactional.id", transactionalId);
        props.setProperty("enable.idempotence", "true");

        KafkaProducer<byte[], byte[]> competing = new KafkaProducer<>(props);
        try {
            competing.initTransactions();
            competing.beginTransaction();
            competing.commitTransaction();
        } finally {
            competing.close();
        }
    }
}
