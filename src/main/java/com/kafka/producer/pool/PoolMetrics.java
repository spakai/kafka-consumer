package com.kafka.producer.pool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Micrometer-based telemetry for the producer pool.
 *
 * <p>Metric names follow the specification (§11):
 * <ul>
 *   <li>{@code pool_size_total} — gauge: total producer slots in the pool</li>
 *   <li>{@code pool_size_ready} — gauge: producers currently in READY state</li>
 *   <li>{@code pool_size_leased} — gauge: producers currently in LEASED state</li>
 *   <li>{@code lease_wait_ms} — timer histogram: time spent waiting for a lease</li>
 *   <li>{@code lease_timeout_total} — counter: lease requests that timed out</li>
 *   <li>{@code transaction_begin_total} — counter</li>
 *   <li>{@code transaction_commit_total} — counter</li>
 *   <li>{@code transaction_abort_total} — counter</li>
 *   <li>{@code transaction_duration_ms} — timer histogram: full transaction duration</li>
 *   <li>{@code producer_fenced_total} — counter</li>
 *   <li>{@code producer_recovery_total} — counter</li>
 *   <li>{@code publish_retry_total} — counter (tagged by {@code error_class})</li>
 * </ul>
 */
public final class PoolMetrics {

    private final Counter leaseTimeoutCounter;
    private final Counter transactionBeginCounter;
    private final Counter transactionCommitCounter;
    private final Counter transactionAbortCounter;
    private final Counter producerFencedCounter;
    private final Timer leaseWaitTimer;
    private final Timer transactionDurationTimer;
    private final MeterRegistry registry;

    /**
     * Register all meters on the supplied registry.
     *
     * @param registry     Micrometer meter registry
     * @param totalSize    supplier for {@code pool_size_total} gauge
     * @param readyCount   supplier for {@code pool_size_ready} gauge
     * @param leasedCount  supplier for {@code pool_size_leased} gauge
     */
    public PoolMetrics(MeterRegistry registry,
                       Supplier<Integer> totalSize,
                       Supplier<Integer> readyCount,
                       Supplier<Integer> leasedCount,
                       Supplier<PoolState> poolState) {
        this.registry = registry;

        Gauge.builder("pool_size_total", totalSize, Supplier::get)
                .strongReference(true)
                .description("Total number of producer slots in the pool")
                .register(registry);
        Gauge.builder("pool_size_ready", readyCount, Supplier::get)
                .strongReference(true)
                .description("Producers currently available (READY state)")
                .register(registry);
        Gauge.builder("pool_size_leased", leasedCount, Supplier::get)
                .strongReference(true)
                .description("Producers currently in active use (LEASED state)")
                .register(registry);
        for (PoolState state : PoolState.values()) {
            Gauge.builder("pool_health", poolState,
                            supplier -> supplier.get() == state ? 1.0 : 0.0)
                    .strongReference(true)
                    .tag("state", state.name())
                    .description("One-hot producer pool lifecycle state")
                    .register(registry);
        }

        this.leaseWaitTimer = Timer.builder("lease_wait_ms")
                .description("Time waiting to acquire a producer lease")
                .register(registry);
        this.leaseTimeoutCounter = Counter.builder("lease_timeout_total")
                .description("Number of lease acquisition timeouts")
                .register(registry);
        this.transactionBeginCounter = Counter.builder("transaction_begin_total")
                .description("Number of transactions begun")
                .register(registry);
        this.transactionCommitCounter = Counter.builder("transaction_commit_total")
                .description("Number of transactions committed successfully")
                .register(registry);
        this.transactionAbortCounter = Counter.builder("transaction_abort_total")
                .description("Number of transactions aborted")
                .register(registry);
        this.transactionDurationTimer = Timer.builder("transaction_duration_ms")
                .description("End-to-end duration of a transaction (begin to commit/abort)")
                .register(registry);
        this.producerFencedCounter = Counter.builder("producer_fenced_total")
                .description("Number of producer fencing events")
                .register(registry);
    }

    // --- Recording methods ---

    public void recordLeaseWait(long nanos) {
        leaseWaitTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordLeaseTimeout() {
        leaseTimeoutCounter.increment();
    }

    public void recordTransactionBegin() {
        transactionBeginCounter.increment();
    }

    public void recordTransactionCommit() {
        transactionCommitCounter.increment();
        recordTransactionOutcome(TransactionOutcome.COMMITTED);
    }

    public void recordTransactionAbort() {
        transactionAbortCounter.increment();
        recordTransactionOutcome(TransactionOutcome.ABORTED);
    }

    public void recordTransactionDuration(long nanos) {
        transactionDurationTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordProducerFenced() {
        producerFencedCounter.increment();
    }

    public void recordProducerRecovery(String outcome) {
        registry.counter("producer_recovery_total", "outcome", outcome).increment();
    }

    public void recordPublishRetry(ErrorClass errorClass) {
        registry.counter("publish_retry_total", "error_class", errorClass.name()).increment();
    }

    public void recordTransactionOutcome(TransactionOutcome outcome) {
        registry.counter("transaction_outcome_total", "outcome", outcome.name()).increment();
    }
}
