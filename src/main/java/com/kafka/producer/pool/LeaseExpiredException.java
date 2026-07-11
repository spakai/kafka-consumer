package com.kafka.producer.pool;

/**
 * Thrown when a hard lease deadline is exceeded while a transaction is in progress.
 * The transaction will be aborted and the producer returned to the pool.
 */
public class LeaseExpiredException extends RuntimeException {

    public LeaseExpiredException(String message) {
        super(message);
    }
}
