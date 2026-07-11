package com.kafka.producer.pool;

/** Externally observable health status of the producer pool. */
public enum PoolHealth {
    /** All producers healthy and available above minimum threshold. */
    HEALTHY,
    /** Operational but below minimum healthy threshold; degraded capacity. */
    DEGRADED,
    /** No producers available; pool cannot serve requests. */
    UNAVAILABLE
}
