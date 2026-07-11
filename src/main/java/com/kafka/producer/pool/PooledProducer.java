package com.kafka.producer.pool;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe wrapper around a single {@link KafkaProducer} with enforced state transitions.
 *
 * <p>State transitions are protected by {@link AtomicReference} compare-and-set, so concurrent
 * lease attempts on the same producer will be rejected by the caller.
 *
 * <p>All transaction operations (begin / send / flush / commit / abort) are delegated directly
 * to the underlying KafkaProducer; this class adds state guards and structured logging.
 */
public final class PooledProducer {

    private static final Logger log = LoggerFactory.getLogger(PooledProducer.class);

    private final String transactionalId;
    private final int slotIndex;
    private final KafkaProducer<byte[], byte[]> producer;
    private final AtomicReference<ProducerState> state;

    public PooledProducer(String transactionalId, int slotIndex,
                          KafkaProducer<byte[], byte[]> producer) {
        this.transactionalId = transactionalId;
        this.slotIndex = slotIndex;
        this.producer = producer;
        this.state = new AtomicReference<>(ProducerState.INITIALIZING);
    }

    /**
     * Call {@code KafkaProducer.initTransactions()} and transition to READY.
     *
     * @throws Exception if initTransactions throws
     */
    public void initTransactions() {
        log.info("Initialising transactions for producer transactionalId={}", transactionalId);
        producer.initTransactions();
        state.set(ProducerState.READY);
        log.info("Producer ready transactionalId={}", transactionalId);
    }

    /**
     * Attempt an atomic state transition from {@code expected} to {@code target}.
     *
     * @param expected current state required for the transition to succeed
     * @param target   desired new state
     * @return {@code true} if the transition succeeded; {@code false} if the current
     *         state did not match {@code expected}
     */
    public boolean transitionTo(ProducerState expected, ProducerState target) {
        boolean succeeded = state.compareAndSet(expected, target);
        if (succeeded) {
            log.debug("Producer state transition transactionalId={} {} → {}",
                    transactionalId, expected, target);
        }
        return succeeded;
    }

    /**
     * Force-set the state without CAS (used during shutdown / eviction).
     */
    public void forceState(ProducerState newState) {
        ProducerState prev = state.getAndSet(newState);
        log.debug("Producer state forced transactionalId={} {} → {}",
                transactionalId, prev, newState);
    }

    public ProducerState getState() {
        return state.get();
    }

    // --- Transaction operations ---

    public void beginTransaction() {
        log.debug("beginTransaction transactionalId={}", transactionalId);
        producer.beginTransaction();
    }

    public Future<RecordMetadata> send(ProducerRecord<byte[], byte[]> record) {
        return producer.send(record);
    }

    public void flush() {
        log.debug("flush transactionalId={}", transactionalId);
        producer.flush();
    }

    public void commitTransaction() {
        log.debug("commitTransaction transactionalId={}", transactionalId);
        producer.commitTransaction();
    }

    public void abortTransaction() {
        log.debug("abortTransaction transactionalId={}", transactionalId);
        producer.abortTransaction();
    }

    /**
     * Close the underlying producer and mark as CLOSED.
     */
    public void close() {
        log.info("Closing producer transactionalId={}", transactionalId);
        state.set(ProducerState.CLOSED);
        try {
            producer.close(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Exception closing producer transactionalId={}", transactionalId, e);
        }
    }

    // --- Accessors ---

    public String getTransactionalId() { return transactionalId; }
    public int getSlotIndex() { return slotIndex; }
}
