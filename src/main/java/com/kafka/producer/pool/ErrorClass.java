package com.kafka.producer.pool;

/**
 * Classification of errors encountered during Kafka producer operations.
 *
 * <p>Used to drive retry, abort, and eviction decisions inside the pool.
 */
public enum ErrorClass {
    /**
     * The operation may be retried safely. The transaction must be aborted
     * before retrying the entire transaction scope.
     */
    RETRIABLE,

    /**
     * The current transaction must be aborted immediately; the producer
     * itself can be returned to the pool for future transactions.
     */
    ABORT_REQUIRED,

    /**
     * The producer is permanently invalidated (e.g. ProducerFencedException).
     * It must be evicted from the pool and replaced asynchronously.
     */
    FATAL
}
