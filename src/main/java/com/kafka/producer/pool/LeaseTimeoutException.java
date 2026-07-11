package com.kafka.producer.pool;

/** Thrown when a lease cannot be acquired within the configured timeout. */
public class LeaseTimeoutException extends RuntimeException {

    public LeaseTimeoutException(String message) {
        super(message);
    }

    public LeaseTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
