package com.kafka.producer.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Asynchronously recreates a producer slot after a fatal error.
 *
 * <p>The number of simultaneous recovery tasks is bounded by
 * {@link PoolConfig#getRecoveryMaxConcurrentRebuilds()} via a {@link Semaphore},
 * preventing "recovery churn storms" during broker instability.
 *
 * <p>When recovery succeeds the newly initialised {@link PooledProducer} is passed
 * to the {@code onRecovered} callback supplied by the pool, which returns it to the
 * available-producer queue.
 *
 * <p>When recovery fails, the event is logged at ERROR level; the pool automatically
 * becomes DEGRADED until another slot is healthy.
 */
public final class RecoverySupervisor {

    private static final Logger log = LoggerFactory.getLogger(RecoverySupervisor.class);

    private final PoolConfig config;
    private final KafkaProducerFactory factory;
    private final Semaphore concurrencyGuard;
    private final ExecutorService executor;
    private volatile boolean shutdown = false;

    public RecoverySupervisor(PoolConfig config, KafkaProducerFactory factory) {
        this.config = config;
        this.factory = factory;
        this.concurrencyGuard = new Semaphore(config.getRecoveryMaxConcurrentRebuilds());
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "kafka-pool-recovery");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedule asynchronous recovery for a faulted producer slot.
     *
     * <p>The slot index from {@code faulted} is reused so the transactional.id
     * remains the same (broker will fence the old epoch automatically).
     *
     * @param faulted      the producer that was evicted
     * @param onRecovered  callback invoked with the new READY producer on success
     * @param onFailed     callback invoked with the exception on failure
     */
    public void scheduleRecovery(PooledProducer faulted,
                                 Consumer<PooledProducer> onRecovered,
                                 Consumer<Exception> onFailed) {
        if (shutdown) {
            log.warn("Recovery requested after shutdown — ignoring transactionalId={}",
                    faulted.getTransactionalId());
            return;
        }

        log.info("Scheduling recovery for transactionalId={} slot={}",
                faulted.getTransactionalId(), faulted.getSlotIndex());

        executor.submit(() -> {
            boolean acquired = false;
            try {
                while (!shutdown && !(acquired = concurrencyGuard.tryAcquire(1, TimeUnit.SECONDS))) {
                    log.warn("Recovery concurrency limit reached; deferring recovery for transactionalId={}", faulted.getTransactionalId());
                }
                if (shutdown) return;

                PooledProducer replacement = buildReplacement(faulted.getSlotIndex());
                log.info("Recovery succeeded transactionalId={}", replacement.getTransactionalId());
                onRecovered.accept(replacement);
            } catch (Exception e) {
                if (e instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    onFailed.accept(ie);
                } else {
                    log.error("Recovery failed for slot={} transactionalId={}", faulted.getSlotIndex(), faulted.getTransactionalId(), e);
                    onFailed.accept(e);
                }
            } finally {
                if (acquired) concurrencyGuard.release();
            }
        });
    }

    /**
     * Create and initialise a replacement producer for the given slot index.
     */
    private PooledProducer buildReplacement(int slotIndex) {
        String txId = config.buildTransactionalId(slotIndex);
        Properties props = buildProducerProperties(txId);
        PooledProducer p = new PooledProducer(txId, slotIndex, factory.create(props));
        p.initTransactions();
        return p;
    }

    /**
     * Build the Kafka producer properties with required transactional and idempotent settings.
     */
    Properties buildProducerProperties(String transactionalId) {
        Properties props = config.getKafkaProperties();
        props.setProperty("transactional.id", transactionalId);
        props.setProperty("enable.idempotence", "true");
        props.setProperty("acks", "all");
        // key.serializer and value.serializer expected from config.kafkaProperties
        if (!props.containsKey("key.serializer")) {
            props.setProperty("key.serializer",
                    "org.apache.kafka.common.serialization.ByteArraySerializer");
        }
        if (!props.containsKey("value.serializer")) {
            props.setProperty("value.serializer",
                    "org.apache.kafka.common.serialization.ByteArraySerializer");
        }
        return props;
    }

    /**
     * Shut down the recovery executor; waits up to 5 seconds for in-flight tasks.
     */
    public void shutdown() {
        shutdown = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
