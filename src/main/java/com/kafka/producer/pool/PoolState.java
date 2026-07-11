package com.kafka.producer.pool;

/**
 * Lifecycle states for the producer pool as a whole.
 *
 * <p>Valid transitions:
 * <ul>
 *   <li>STARTING → HEALTHY (minimum threshold met)</li>
 *   <li>STARTING → STOPPED (startup threshold not met)</li>
 *   <li>HEALTHY → DEGRADED (available producers drop below minHealthy)</li>
 *   <li>DEGRADED → HEALTHY (recovered producers restore threshold)</li>
 *   <li>HEALTHY | DEGRADED → DRAINING (shutdown initiated)</li>
 *   <li>DRAINING → STOPPED (all producers closed)</li>
 * </ul>
 */
public enum PoolState {
    STARTING,
    HEALTHY,
    DEGRADED,
    DRAINING,
    STOPPED
}
