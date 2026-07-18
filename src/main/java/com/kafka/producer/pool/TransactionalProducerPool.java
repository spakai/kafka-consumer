package com.kafka.producer.pool;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production-grade pooled transactional Kafka producer.
 *
 * <h2>Usage (preferred API)</h2>
 * <pre>{@code
 * TransactionalProducerPool pool = TransactionalProducerPool.create(config);
 * pool.initialize();
 *
 * String result = pool.executeInTransaction(lease -> {
 *     lease.send(new ProducerRecord<>("my-topic", key, value));
 *     return "ok";
 * }, ExecutionOptions.defaults());
 *
 * pool.shutdown();
 * }</pre>
 *
 * <h2>Transaction execution rules (§9)</h2>
 * <ol>
 *   <li>Acquire producer lease.</li>
 *   <li>Begin transaction.</li>
 *   <li>Execute client callback to publish records.</li>
 *   <li>Flush pending sends.</li>
 *   <li>Commit transaction.</li>
 *   <li>On any exception: abort (if transaction started), classify, retry or fail.</li>
 *   <li>Release lease in finally path (always).</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * All public methods are thread-safe. The available-producer queue is a
 * {@link LinkedBlockingQueue}; pool-state transitions are guarded by
 * {@link AtomicReference} CAS operations.
 */
public final class TransactionalProducerPool {

    private static final Logger log = LoggerFactory.getLogger(TransactionalProducerPool.class);

    private final PoolConfig config;
    private final KafkaProducerFactory factory;
    private final PoolMetrics metrics;
    private final RecoverySupervisor recoverySupervisor;

    /** Producers available for lease. */
    private final LinkedBlockingQueue<PooledProducer> available;
    /** All producers managed by the pool (including leased ones). */
    private final Set<PooledProducer> allProducers;
    /** Count of producers currently in LEASED state. */
    private final AtomicInteger leasedCount;
    /** Count of producers currently in READY state. */
    private final AtomicInteger readyCount;

    private final AtomicReference<PoolState> poolState;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Create a pool with a custom producer factory and a custom meter registry.
     */
    public TransactionalProducerPool(PoolConfig config,
                                     KafkaProducerFactory factory,
                                     MeterRegistry meterRegistry) {
        this.config = config;
        this.factory = factory;
        this.available = new LinkedBlockingQueue<>();
        this.allProducers = new CopyOnWriteArraySet<>();
        this.leasedCount = new AtomicInteger(0);
        this.readyCount = new AtomicInteger(0);
        this.poolState = new AtomicReference<>(PoolState.STARTING);
        this.recoverySupervisor = new RecoverySupervisor(config, factory);
        this.metrics = new PoolMetrics(
                meterRegistry,
                () -> allProducers.size(),
                readyCount::get,
                leasedCount::get,
                poolState::get);
    }

    /**
     * Convenience factory: uses {@link DefaultKafkaProducerFactory} and a
     * {@link SimpleMeterRegistry} (replace with your application registry).
     */
    public static TransactionalProducerPool create(PoolConfig config) {
        return new TransactionalProducerPool(config,
                new DefaultKafkaProducerFactory(),
                new SimpleMeterRegistry());
    }

    // -----------------------------------------------------------------------
    // Initialisation (FR-1)
    // -----------------------------------------------------------------------

    /**
     * Initialise the producer pool: creates {@code pool.size} producers, calls
     * {@code initTransactions} on each, and verifies that at least
     * {@code pool.minHealthy} producers are ready.
     *
     * @throws ProducerInitializationException if the minimum healthy threshold is not met
     */
    public void initialize() {
        log.info("Initialising TransactionalProducerPool size={} minHealthy={}",
                config.getPoolSize(), config.getMinHealthyProducers());
        poolState.set(PoolState.STARTING);
        int healthy = 0;
        for (int i = 0; i < config.getPoolSize(); i++) {
            try {
                PooledProducer p = createProducer(i);
                allProducers.add(p);
                available.add(p);
                readyCount.incrementAndGet();
                healthy++;
            } catch (Exception e) {
                log.error("Failed to initialise producer slot={}", i, e);
            }
        }
        if (healthy < config.getMinHealthyProducers()) {
            // Best-effort cleanup of any producers that were successfully created.
            shutdown();
            throw new ProducerInitializationException(
                    "Pool startup failed: only " + healthy + " of " +
                    config.getPoolSize() + " producers initialised " +
                    "(minimum required: " + config.getMinHealthyProducers() + ")");
        }
        poolState.set(healthy == config.getPoolSize() ? PoolState.HEALTHY : PoolState.DEGRADED);
        log.info("Pool initialised healthy={} total={} state={}",
                healthy, config.getPoolSize(), poolState.get());
    }

    // -----------------------------------------------------------------------
    // Lease API (FR-2)
    // -----------------------------------------------------------------------

    /**
     * Acquire a producer lease, blocking up to {@code timeoutMs} milliseconds.
     *
     * @param timeoutMs maximum time to wait for an available producer
     * @return an active producer lease
     * @throws PoolShutdownException   if the pool is draining or stopped
     * @throws LeaseTimeoutException   if no producer becomes available within the timeout
     * @throws PoolSaturationException if all producers are leased and timeout elapses
     */
    public ProducerLease acquireLease(long timeoutMs) {
        PoolState current = poolState.get();
        if (current == PoolState.DRAINING || current == PoolState.STOPPED) {
            throw new PoolShutdownException("Pool is " + current + "; no new leases accepted");
        }

        long started = System.nanoTime();
        PooledProducer producer;
        try {
            producer = available.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LeaseTimeoutException("Interrupted while waiting for a producer lease", e);
        }
        long waited = System.nanoTime() - started;
        metrics.recordLeaseWait(waited);

        if (producer == null) {
            metrics.recordLeaseTimeout();
            throw new PoolSaturationException(
                    "No producer available after " + timeoutMs + " ms; pool is saturated");
        }

        // Mark as LEASED
        producer.forceState(ProducerState.LEASED);
        leasedCount.incrementAndGet();
        readyCount.decrementAndGet();

        ProducerLease lease = new ProducerLease(producer, config.getLeaseHardTimeoutMs(),
                null);
        log.debug("Lease acquired leaseId={} transactionalId={} waited={}ms",
                lease.getLeaseId(), lease.getTransactionalId(),
                TimeUnit.NANOSECONDS.toMillis(waited));
        return lease;
    }

    /**
     * Overload that uses the pool-level default lease timeout.
     */
    public ProducerLease acquireLease() {
        return acquireLease(config.getLeaseTimeoutMs());
    }

    // -----------------------------------------------------------------------
    // Transaction lifecycle (FR-3)
    // -----------------------------------------------------------------------

    /**
     * Begin a Kafka transaction on the leased producer.
     *
     * @param lease active lease
     */
    public void beginTransaction(ProducerLease lease) {
        checkLeaseExpiry(lease);
        lease.getProducer().beginTransaction();
        metrics.recordTransactionBegin();
        log.debug("Transaction begun leaseId={} transactionalId={}",
                lease.getLeaseId(), lease.getTransactionalId());
    }

    /**
     * Send a record within the active transaction.
     *
     * @param lease  active lease
     * @param record record to send
     * @return future completing with metadata when the record is acknowledged
     */
    public Future<RecordMetadata> send(ProducerLease lease,
                                       ProducerRecord<byte[], byte[]> record) {
        checkLeaseExpiry(lease);
        return lease.send(record);
    }

    /**
     * Flush pending sends and commit the active transaction.
     *
     * <p>If the commit fails due to an ambiguous timeout, the producer is evicted
     * (Scenario C — commit outcome unknown, producer quarantined).
     *
     * @param lease active lease
     */
    public void commit(ProducerLease lease) {
        checkLeaseExpiry(lease);
        try {
            lease.getProducer().flush();
            lease.getProducer().commitTransaction();
            metrics.recordTransactionCommit();
            log.debug("Transaction committed leaseId={} transactionalId={}",
                    lease.getLeaseId(), lease.getTransactionalId());
        } catch (Exception e) {
            ErrorClass ec = ErrorClassifier.classify(e, true);
            if (ec == ErrorClass.FATAL) {
                if (e instanceof org.apache.kafka.common.errors.TimeoutException) {
                    metrics.recordTransactionOutcome(TransactionOutcome.AMBIGUOUS);
                }
                if (isFencingFailure(e)) {
                    metrics.recordProducerFenced();
                }
                log.error("Fatal error during commit — evicting producer leaseId={} " +
                        "transactionalId={}", lease.getLeaseId(),
                        lease.getTransactionalId(), e);
                evictProducer(lease);
            }
            throw toRuntimeException(e);
        }
    }

    /**
     * Abort the active transaction.
     *
     * @param lease active lease
     */
    public void abort(ProducerLease lease) {
        safeAbort(lease);
    }

    /**
     * Return the producer to the pool.
     *
     * <p>Only call this once per lease. After this call the lease is invalid.
     *
     * @param lease active lease
     */
    public void release(ProducerLease lease) {
        if (!lease.markReleased()) {
            throw new IllegalStateException(
                    "Lease " + lease.getLeaseId() + " has already been released");
        }
        returnToPool(lease.getProducer(), false);
    }

    // -----------------------------------------------------------------------
    // executeInTransaction — preferred integration API (§8, §9)
    // -----------------------------------------------------------------------

    /**
     * Execute {@code callback} within a managed transaction scope.
     *
     * <ol>
     *   <li>Acquire a producer lease.</li>
     *   <li>Begin transaction.</li>
     *   <li>Invoke callback.</li>
     *   <li>Flush + commit on success.</li>
     *   <li>Abort on failure; classify error and retry/evict/fail.</li>
     *   <li>Always release the lease.</li>
     * </ol>
     *
     * @param callback callback that sends records
     * @param options  per-call options (timeouts, retry count, correlation id)
     * @param <T>      return type of the callback
     * @return the value returned by {@code callback}
     * @throws RuntimeException if the transaction ultimately fails
     */
    public <T> T executeInTransaction(TransactionCallback<T> callback,
                                      ExecutionOptions options) {
        long leaseTimeout = options.getLeaseTimeoutMs() == ExecutionOptions.USE_POOL_DEFAULT_TIMEOUT
                ? config.getLeaseTimeoutMs()
                : options.getLeaseTimeoutMs();
        int maxAttempts = options.getMaxRetryAttempts() == ExecutionOptions.USE_POOL_DEFAULT
                ? config.getRetryMaxAttempts() + 1
                : options.getMaxRetryAttempts() + 1;

        ProducerLease lease;
        try {
            lease = acquireLease(leaseTimeout);
        } catch (LeaseTimeoutException | PoolSaturationException | PoolShutdownException e) {
            metrics.recordTransactionOutcome(TransactionOutcome.REJECTED);
            throw e;
        }
        log.debug("executeInTransaction leaseId={} transactionalId={} correlationId={}",
                lease.getLeaseId(), lease.getTransactionalId(), options.getCorrelationId());

        boolean producerEvicted = false;
        long txStart = System.nanoTime();
        Exception lastException = null;

        try {
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                if (attempt > 0) {
                    applyBackoff(attempt, options);
                }
                boolean transactionStarted = false;
                boolean commitStarted = false;
                try {
                    // Check hard deadline before each attempt
                    if (lease.isExpired()) {
                        throw new LeaseExpiredException(
                                "Lease expired before attempt " + attempt +
                                " (leaseId=" + lease.getLeaseId() + ")");
                    }

                    beginTransaction(lease);
                    transactionStarted = true;

                    T result = callback.execute(lease);

                    // Flush then commit
                    commitStarted = true;
                    lease.getProducer().flush();
                    lease.getProducer().commitTransaction();
                    metrics.recordTransactionCommit();
                    // duration recorded once in finally

                    log.info("Transaction committed leaseId={} transactionalId={} attempt={}",
                            lease.getLeaseId(), lease.getTransactionalId(), attempt);
                    return result;

                } catch (Exception e) {
                    lastException = e;
                    ErrorClass ec = ErrorClassifier.classify(e, commitStarted);

                    log.warn("Transaction error attempt={}/{} leaseId={} transactionalId={} " +
                            "errorClass={} error={}",
                            attempt + 1, maxAttempts,
                            lease.getLeaseId(), lease.getTransactionalId(),
                            ec, e.getMessage());

                    if (transactionStarted && ec != ErrorClass.FATAL) {
                        safeAbort(lease);
                    }

                    if (ec == ErrorClass.FATAL) {
                        if (commitStarted && e instanceof org.apache.kafka.common.errors.TimeoutException) {
                            metrics.recordTransactionOutcome(TransactionOutcome.AMBIGUOUS);
                        }
                        if (isFencingFailure(e)) {
                            metrics.recordProducerFenced();
                        }
                        evictProducer(lease);
                        producerEvicted = true;
                        throw toRuntimeException(e);
                    }

                    if (ec == ErrorClass.RETRIABLE && attempt < maxAttempts - 1) {
                        metrics.recordPublishRetry(ec);
                        // continue to next attempt
                        continue;
                    }

                    // ABORT_REQUIRED or retriable exhausted → propagate
                    metrics.recordPublishRetry(ec);
                    throw toRuntimeException(e);
                }
            }
            // All attempts exhausted with retriable errors
            throw new RuntimeException(
                    "All " + maxAttempts + " transaction attempts exhausted", lastException);

        } finally {
            metrics.recordTransactionDuration(System.nanoTime() - txStart);
            if (!producerEvicted) {
                lease.markReleased();
                returnToPool(lease.getProducer(), false);
            }
        }
    }

    /**
     * Convenience overload using default options.
     */
    public <T> T executeInTransaction(TransactionCallback<T> callback) {
        return executeInTransaction(callback, ExecutionOptions.defaults());
    }

    // -----------------------------------------------------------------------
    // Shutdown (FR-7)
    // -----------------------------------------------------------------------

    /**
     * Gracefully shut down the pool.
     *
     * <ol>
     *   <li>Transition to DRAINING; refuse new lease requests.</li>
     *   <li>Wait up to {@code shutdown.gracePeriod.ms} for in-flight leases to complete.</li>
     *   <li>Force-close all remaining producers.</li>
     *   <li>Shut down the recovery executor.</li>
     * </ol>
     */
    public void shutdown() {
        if (!poolState.compareAndSet(PoolState.HEALTHY, PoolState.DRAINING) &&
                !poolState.compareAndSet(PoolState.DEGRADED, PoolState.DRAINING)) {
            log.warn("shutdown() called in unexpected pool state {}", poolState.get());
        }
        log.info("Pool draining — waiting up to {}ms for in-flight leases",
                config.getShutdownGracePeriodMs());

        long deadline = System.currentTimeMillis() + config.getShutdownGracePeriodMs();
        while (leasedCount.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (leasedCount.get() > 0) {
            log.warn("Grace period elapsed; {} producers still leased — aborting", leasedCount.get());
        }

        // Close all producers
        for (PooledProducer p : allProducers) {
            ProducerState s = p.getState();
            if (s == ProducerState.LEASED) {
                try {
                    p.abortTransaction();
                } catch (Exception e) {
                    log.warn("Error aborting transaction during shutdown transactionalId={}",
                            p.getTransactionalId(), e);
                }
            }
            p.close();
        }

        recoverySupervisor.shutdown();
        poolState.set(PoolState.STOPPED);
        log.info("Pool stopped");
    }

    // -----------------------------------------------------------------------
    // Health (NFR-3)
    // -----------------------------------------------------------------------

    /**
     * Return the current health of the pool.
     *
     * @return HEALTHY, DEGRADED, or UNAVAILABLE
     */
    public PoolHealth getHealth() {
        return switch (poolState.get()) {
            case HEALTHY -> PoolHealth.HEALTHY;
            case DEGRADED -> PoolHealth.DEGRADED;
            case STARTING, DRAINING, STOPPED -> PoolHealth.UNAVAILABLE;
        };
    }

    public PoolState getPoolState() {
        return poolState.get();
    }

    public int getReadyCount() { return readyCount.get(); }
    public int getLeasedCount() { return leasedCount.get(); }
    public int getTotalCount() { return allProducers.size(); }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private PooledProducer createProducer(int slotIndex) {
        String txId = config.buildTransactionalId(slotIndex);
        Properties props = buildProducerProperties(txId);
        PooledProducer p = new PooledProducer(txId, slotIndex, factory.create(props));
        p.initTransactions();
        return p;
    }

    Properties buildProducerProperties(String transactionalId) {
        Properties props = config.getKafkaProperties();
        props.setProperty("transactional.id", transactionalId);
        props.setProperty("enable.idempotence", "true");
        props.setProperty("acks", "all");
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

    private void checkLeaseExpiry(ProducerLease lease) {
        lease.ensureActive();
        if (lease.isExpired()) {
            throw new LeaseExpiredException("Lease " + lease.getLeaseId() + " has expired");
        }
    }

    /**
     * Safely abort the active transaction, swallowing errors (already in error path).
     */
    private void safeAbort(ProducerLease lease) {
        try {
            lease.getProducer().abortTransaction();
            metrics.recordTransactionAbort();
            log.debug("Transaction aborted leaseId={}", lease.getLeaseId());
        } catch (Exception ex) {
            log.warn("Error aborting transaction leaseId={}", lease.getLeaseId(), ex);
        }
    }

    /**
     * Evict a producer: close it, trigger async recovery, and update pool state.
     */
    private void evictProducer(ProducerLease lease) {
        lease.markReleased();
        PooledProducer faulted = lease.getProducer();
        log.error("Evicting producer transactionalId={} leaseId={}",
                faulted.getTransactionalId(), lease.getLeaseId());

        allProducers.remove(faulted);
        leasedCount.decrementAndGet();
        faulted.forceState(ProducerState.RECOVERING);
        try {
            faulted.close();
        } catch (Exception e) {
            log.warn("Error closing evicted producer", e);
        }

        updatePoolState();

        recoverySupervisor.scheduleRecovery(
                faulted,
                replacement -> {
                    metrics.recordProducerRecovery("SUCCESS");
                    allProducers.add(replacement);
                    returnToPool(replacement, true);
                },
                ex -> {
                    metrics.recordProducerRecovery("FAILED");
                    log.error("Producer recovery failed permanently for slot={}",
                            faulted.getSlotIndex(), ex);
                    updatePoolState();
                }
        );
    }

    /**
     * Return a producer to the available queue (or do nothing if closing).
     *
     * @param producer    the producer to return
     * @param isRecovered {@code true} if this is a freshly recovered producer
     *                    (already in READY state); {@code false} if returning from a lease
     */
    private void returnToPool(PooledProducer producer, boolean isRecovered) {
        PoolState ps = poolState.get();
        if (ps == PoolState.DRAINING || ps == PoolState.STOPPED) {
            if (!isRecovered) {
                leasedCount.decrementAndGet();
            }
            producer.close();
            return;
        }

        if (!isRecovered) {
            producer.forceState(ProducerState.READY);
            leasedCount.decrementAndGet();
        }
        readyCount.incrementAndGet();
        available.add(producer);
        updatePoolState();

        log.debug("Producer returned to pool transactionalId={}", producer.getTransactionalId());
    }

    /**
     * Recompute and update pool state (HEALTHY / DEGRADED) based on current counts.
     */
    private void updatePoolState() {
        int healthy = readyCount.get() + leasedCount.get();
        PoolState current = poolState.get();
        if (current == PoolState.DRAINING || current == PoolState.STOPPED) return;

        PoolState next = healthy >= config.getMinHealthyProducers()
                ? PoolState.HEALTHY
                : PoolState.DEGRADED;
        if (poolState.compareAndSet(current, next) && current != next) {
            log.info("Pool state changed {} → {} (ready={} leased={})",
                    current, next, readyCount.get(), leasedCount.get());
        }
    }

    private void applyBackoff(int attempt, ExecutionOptions options) {
        long delay = Math.min(
                config.getRetryBaseDelayMs() * (1L << (attempt - 1)),
                config.getRetryMaxDelayMs());
        // Add jitter: ±20 %
        delay = (long) (delay * (0.8 + Math.random() * 0.4));
        log.debug("Retry backoff attempt={} delayMs={}", attempt, delay);
        try {
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException toRuntimeException(Exception e) {
        if (e instanceof RuntimeException re) return re;
        return new RuntimeException(e);
    }

    private static boolean isFencingFailure(Exception e) {
        return e instanceof org.apache.kafka.common.errors.ProducerFencedException
                || e instanceof org.apache.kafka.common.errors.InvalidProducerEpochException
                || e instanceof org.apache.kafka.common.errors.OutOfOrderSequenceException;
    }
}
