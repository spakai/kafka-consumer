package com.kafka.producer.pool;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PoolMetricsTest {

    @Test
    void exposesCapacityAndOneHotHealthGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger total = new AtomicInteger(4);
        AtomicInteger ready = new AtomicInteger(3);
        AtomicInteger leased = new AtomicInteger(1);
        AtomicReference<PoolState> state = new AtomicReference<>(PoolState.HEALTHY);

        new PoolMetrics(registry, total::get, ready::get, leased::get, state::get);

        assertEquals(4.0, registry.get("pool_size_total").gauge().value());
        assertEquals(3.0, registry.get("pool_size_ready").gauge().value());
        assertEquals(1.0, registry.get("pool_size_leased").gauge().value());
        assertEquals(1.0, registry.get("pool_health").tag("state", "HEALTHY").gauge().value());
        assertEquals(0.0, registry.get("pool_health").tag("state", "DEGRADED").gauge().value());

        state.set(PoolState.DEGRADED);

        assertEquals(0.0, registry.get("pool_health").tag("state", "HEALTHY").gauge().value());
        assertEquals(1.0, registry.get("pool_health").tag("state", "DEGRADED").gauge().value());
    }

    @Test
    void recordsBoundedOutcomesAndRecoveryResults() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicReference<PoolState> state = new AtomicReference<>(PoolState.STARTING);
        PoolMetrics metrics = new PoolMetrics(registry, () -> 0, () -> 0, () -> 0, state::get);

        metrics.recordTransactionCommit();
        metrics.recordTransactionAbort();
        metrics.recordTransactionOutcome(TransactionOutcome.REJECTED);
        metrics.recordTransactionOutcome(TransactionOutcome.AMBIGUOUS);
        metrics.recordProducerRecovery("SUCCESS");
        metrics.recordProducerRecovery("FAILED");

        for (TransactionOutcome outcome : TransactionOutcome.values()) {
            assertEquals(1.0, registry.get("transaction_outcome_total")
                    .tag("outcome", outcome.name()).counter().count());
        }
        assertEquals(1.0, registry.get("producer_recovery_total")
                .tag("outcome", "SUCCESS").counter().count());
        assertEquals(1.0, registry.get("producer_recovery_total")
                .tag("outcome", "FAILED").counter().count());
        assertNotNull(registry.find("transaction_commit_total").counter());
        assertNotNull(registry.find("transaction_abort_total").counter());
    }
}
