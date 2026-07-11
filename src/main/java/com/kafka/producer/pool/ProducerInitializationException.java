package com.kafka.producer.pool;

/** Thrown when a producer cannot be initialised during pool startup or recovery. */
public class ProducerInitializationException extends RuntimeException {

    public ProducerInitializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProducerInitializationException(String message) {
        super(message);
    }
}
