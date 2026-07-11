package com.kafka.producer.pool;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Represents a temporary, exclusive right to use a single {@link PooledProducer}.
 *
 * <p>A lease is created by {@link TransactionalProducerPool#acquireLease} and released
 * (returned to the pool) via {@link TransactionalProducerPool#release} or implicitly
 * by {@link TransactionalProducerPool#executeInTransaction}.
 *
 * <p>The lease carries a <em>hard deadline</em>: if {@link #isExpired()} returns {@code true},
 * the pool will abort any active transaction and refuse further sends on this lease.
 */
public final class ProducerLease {

    private final String leaseId;
    private final PooledProducer producer;
    private final Instant acquiredAt;
    private final Instant hardDeadline;
    private final String correlationId;

    /**
     * Create a new lease.
     *
     * @param producer       the leased producer
     * @param hardTimeoutMs  hard lease deadline in milliseconds from now
     * @param correlationId  caller-supplied correlation id for tracing (may be {@code null})
     */
    public ProducerLease(PooledProducer producer, long hardTimeoutMs, String correlationId) {
        this.leaseId = UUID.randomUUID().toString();
        this.producer = producer;
        this.acquiredAt = Instant.now();
        this.hardDeadline = acquiredAt.plusMillis(hardTimeoutMs);
        this.correlationId = correlationId;
    }

    /**
     * Send a record on this lease.
     *
     * @param record the producer record (must use byte-array key and value)
     * @return a Future that completes when the record is acknowledged
     * @throws LeaseExpiredException if the hard deadline has been exceeded
     */
    public Future<RecordMetadata> send(ProducerRecord<byte[], byte[]> record) {
        if (isExpired()) {
            throw new LeaseExpiredException(
                    "Lease " + leaseId + " has expired (transactionalId=" +
                    producer.getTransactionalId() + ")");
        }
        return producer.send(record);
    }

    /**
     * Returns {@code true} if the hard deadline has been exceeded.
     *
     * <p>When this returns {@code true}, any active transaction will be aborted by the pool.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(hardDeadline);
    }

    // --- Accessors (package-private for pool internals) ---

    /** Globally unique identifier for this lease, used in structured logs. */
    public String getLeaseId() { return leaseId; }

    /** The underlying pooled producer — accessible to pool internals only. */
    PooledProducer getProducer() { return producer; }

    /** Instant this lease was created. */
    public Instant getAcquiredAt() { return acquiredAt; }

    /** Hard deadline after which sends are refused and the transaction is aborted. */
    public Instant getHardDeadline() { return hardDeadline; }

    /** Caller-supplied correlation id (may be {@code null}). */
    public String getCorrelationId() { return correlationId; }

    /** The transactional.id of the underlying producer. */
    public String getTransactionalId() { return producer.getTransactionalId(); }
}
