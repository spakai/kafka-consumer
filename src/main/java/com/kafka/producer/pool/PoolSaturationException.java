package com.kafka.producer.pool;

/**
 * Thrown when all producers are leased and the caller's wait time has elapsed,
 * indicating the pool is saturated beyond its configured capacity.
 */
public class PoolSaturationException extends RuntimeException {

    public PoolSaturationException(String message) {
        super(message);
    }
}
