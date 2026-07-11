package com.kafka.producer.pool;

/** Thrown when a lease request is rejected because the pool is draining or stopped. */
public class PoolShutdownException extends RuntimeException {

    public PoolShutdownException(String message) {
        super(message);
    }
}
