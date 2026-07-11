package com.kafka.producer.perf.scenarios;

import com.kafka.producer.perf.LoadWorker;
import com.kafka.producer.perf.ScenarioConfig;
import com.kafka.producer.pool.PoolConfig;
import com.kafka.producer.pool.ProducerLease;
import com.kafka.producer.pool.TransactionalProducerPool;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ScenarioSupport {
    private ScenarioSupport() {}

    static byte[] payload(int bytes) {
        return "x".repeat(Math.max(1, bytes)).getBytes(StandardCharsets.UTF_8);
    }

    static Properties kafkaProps(ScenarioConfig config) {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", config.bootstrapServers());
        props.setProperty("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.setProperty("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.setProperty("acks", "all");
        props.setProperty("linger.ms", "0");
        return props;
    }

    static PoolConfig poolConfig(ScenarioConfig config, int poolSize) {
        return PoolConfig.builder()
                .poolSize(poolSize)
                .minHealthyProducers(Math.max(1, poolSize / 2))
                .leaseTimeoutMs(config.leaseTimeoutMs())
                .leaseHardTimeoutMs(Math.max(config.leaseTimeoutMs(), 30_000L))
                .shutdownGracePeriodMs(10_000)
                .retryMaxAttempts(2)
                .retryBaseDelayMs(20)
                .retryMaxDelayMs(500)
                .recoveryMaxConcurrentRebuilds(Math.max(1, poolSize / 2))
                .serviceIdentity("perf")
                .instanceIdentifier("runner")
                .kafkaProperties(kafkaProps(config))
                .build();
    }

    static TransactionalProducerPool createPool(ScenarioConfig config, int poolSize) {
        TransactionalProducerPool pool = TransactionalProducerPool.create(poolConfig(config, poolSize));
        pool.initialize();
        return pool;
    }

    static ScenarioStats runWorkers(int threads,
                                    java.time.Duration duration,
                                    LoadWorker.Operation operation,
                                    com.kafka.producer.perf.LatencyRecorder txLatency) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicBoolean stop = new AtomicBoolean(false);
        List<Future<LoadWorker.Stats>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(new LoadWorker(stop, operation, txLatency)));
        }

        Thread.sleep(duration.toMillis());
        stop.set(true);

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        ScenarioStats totals = new ScenarioStats();
        for (Future<LoadWorker.Stats> future : futures) {
            LoadWorker.Stats s = future.get();
            totals.successfulTx += s.successfulTx;
            totals.successfulMessages += s.successfulMessages;
            totals.leaseTimeouts += s.leaseTimeouts;
            totals.saturations += s.saturations;
            totals.failures += s.failures;
        }
        totals.durationSec = duration.toSeconds();
        return totals;
    }

    static LoadWorker.Operation transactionalOperation(TransactionalProducerPool pool,
                                                       String topic,
                                                       byte[] payload,
                                                       int recordsPerTx,
                                                       com.kafka.producer.perf.LatencyRecorder leaseLatency) {
        return () -> {
            long leaseStart = System.nanoTime();
            ProducerLease lease = pool.acquireLease();
            leaseLatency.recordNanos(System.nanoTime() - leaseStart);
            try {
                pool.beginTransaction(lease);
                for (int i = 0; i < recordsPerTx; i++) {
                    pool.send(lease, new ProducerRecord<>(topic, null, payload)).get();
                }
                pool.commit(lease);
                return recordsPerTx;
            } finally {
                pool.release(lease);
            }
        };
    }

    static LoadWorker.Operation nonTransactionalOperation(KafkaProducer<byte[], byte[]> producer,
                                                          String topic,
                                                          byte[] payload) {
        return () -> {
            producer.send(new ProducerRecord<>(topic, null, payload)).get();
            return 1;
        };
    }

    static final class ScenarioStats {
        long successfulTx;
        long successfulMessages;
        long leaseTimeouts;
        long saturations;
        long failures;
        long durationSec;

        double msgPerSec() {
            return durationSec == 0 ? 0 : (double) successfulMessages / durationSec;
        }

        double txPerSec() {
            return durationSec == 0 ? 0 : (double) successfulTx / durationSec;
        }
    }
}
