package com.kafka.producer.pool;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PoolConfig} validation.
 */
class PoolConfigTest {

    private static PoolConfig.Builder validBuilder() {
        return PoolConfig.builder()
                .poolSize(5)
                .minHealthyProducers(2)
                .leaseTimeoutMs(3_000)
                .leaseHardTimeoutMs(30_000)
                .shutdownGracePeriodMs(10_000)
                .retryMaxAttempts(3)
                .retryBaseDelayMs(100)
                .retryMaxDelayMs(5_000)
                .recoveryMaxConcurrentRebuilds(1)
                .serviceIdentity("my-service")
                .instanceIdentifier("instance-1")
                .kafkaProperties(new Properties());
    }

    @Test
    void validConfigBuildsSuccessfully() {
        assertDoesNotThrow(() -> validBuilder().build());
    }

    @Test
    void rejectsZeroPoolSize() {
        assertThrows(IllegalArgumentException.class,
                () -> validBuilder().poolSize(0).build());
    }

    @Test
    void rejectsMinHealthyGreaterThanPoolSize() {
        assertThrows(IllegalArgumentException.class,
                () -> validBuilder().poolSize(3).minHealthyProducers(4).build());
    }

    @Test
    void rejectsZeroLeaseTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> validBuilder().leaseTimeoutMs(0).build());
    }

    @Test
    void rejectsHardTimeoutSmallerThanLeaseTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> validBuilder().leaseTimeoutMs(5_000).leaseHardTimeoutMs(1_000).build());
    }

    @Test
    void rejectsNegativeRetryMaxAttempts() {
        assertThrows(IllegalArgumentException.class,
                () -> validBuilder().retryMaxAttempts(-1).build());
    }

    @Test
    void rejectsRetryMaxDelayLessThanBaseDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> validBuilder().retryBaseDelayMs(500).retryMaxDelayMs(100).build());
    }

    @Test
    void rejectsBlankServiceIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> validBuilder().serviceIdentity("  ").build());
    }

    @Test
    void rejectsBlankInstanceIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> validBuilder().instanceIdentifier("").build());
    }

    @Test
    void buildTransactionalIdFormatsCorrectly() {
        PoolConfig config = validBuilder()
                .serviceIdentity("svc")
                .instanceIdentifier("inst")
                .build();
        assertEquals("svc-inst-0", config.buildTransactionalId(0));
        assertEquals("svc-inst-7", config.buildTransactionalId(7));
    }

    @Test
    void kafkaPropertiesAreCopiedDefensively() {
        Properties original = new Properties();
        original.setProperty("key", "value");
        PoolConfig config = validBuilder().kafkaProperties(original).build();

        // Mutating original should not affect config
        original.setProperty("key", "modified");
        assertEquals("value", config.getKafkaProperties().getProperty("key"));
    }
}
