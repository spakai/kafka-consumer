package com.kafka.producer.pool;

/** Bounded transaction outcomes used by the pool's metrics contract. */
public enum TransactionOutcome {
    COMMITTED,
    ABORTED,
    REJECTED,
    AMBIGUOUS
}
