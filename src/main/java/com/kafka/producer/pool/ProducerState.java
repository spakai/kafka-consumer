package com.kafka.producer.pool;

/**
 * Lifecycle states for a single pooled producer instance.
 *
 * <p>Valid transitions:
 * <ul>
 *   <li>INITIALIZING → READY (after initTransactions succeeds)</li>
 *   <li>READY → LEASED (on lease acquisition)</li>
 *   <li>LEASED → READY (on clean release)</li>
 *   <li>LEASED → RECOVERING (on fatal error / eviction)</li>
 *   <li>RECOVERING → READY (after replacement producer is initialised)</li>
 *   <li>any → CLOSED (on pool shutdown)</li>
 * </ul>
 */
public enum ProducerState {
    INITIALIZING,
    READY,
    LEASED,
    RECOVERING,
    CLOSED
}
