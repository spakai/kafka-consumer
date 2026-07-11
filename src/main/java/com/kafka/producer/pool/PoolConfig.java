package com.kafka.producer.pool;

import java.util.Objects;
import java.util.Properties;

/**
 * Immutable configuration for {@link TransactionalProducerPool}.
 *
 * <p>Use {@link #builder()} to construct an instance; validation is performed
 * at build time and rejects invalid combinations per the specification (§12).
 *
 * <h2>Transactional ID format</h2>
 * Each producer slot's transactional.id is:
 * {@code <serviceIdentity>-<instanceIdentifier>-<slotIndex>}
 * This guarantees uniqueness across slots while being deterministic and human-readable.
 */
public final class PoolConfig {

    private final int poolSize;
    private final int minHealthyProducers;
    private final long leaseTimeoutMs;
    private final long leaseHardTimeoutMs;
    private final long shutdownGracePeriodMs;
    private final int retryMaxAttempts;
    private final long retryBaseDelayMs;
    private final long retryMaxDelayMs;
    private final int recoveryMaxConcurrentRebuilds;
    private final String serviceIdentity;
    private final String instanceIdentifier;
    private final Properties kafkaProperties;

    private PoolConfig(Builder b) {
        this.poolSize = b.poolSize;
        this.minHealthyProducers = b.minHealthyProducers;
        this.leaseTimeoutMs = b.leaseTimeoutMs;
        this.leaseHardTimeoutMs = b.leaseHardTimeoutMs;
        this.shutdownGracePeriodMs = b.shutdownGracePeriodMs;
        this.retryMaxAttempts = b.retryMaxAttempts;
        this.retryBaseDelayMs = b.retryBaseDelayMs;
        this.retryMaxDelayMs = b.retryMaxDelayMs;
        this.recoveryMaxConcurrentRebuilds = b.recoveryMaxConcurrentRebuilds;
        this.serviceIdentity = b.serviceIdentity;
        this.instanceIdentifier = b.instanceIdentifier;
        this.kafkaProperties = new Properties();
        this.kafkaProperties.putAll(b.kafkaProperties);
        validate();
    }

    private void validate() {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("pool.size must be > 0, got " + poolSize);
        }
        if (minHealthyProducers <= 0 || minHealthyProducers > poolSize) {
            throw new IllegalArgumentException(
                    "pool.minHealthy must be in [1, pool.size]; got " + minHealthyProducers);
        }
        if (leaseTimeoutMs <= 0) {
            throw new IllegalArgumentException("lease.timeout.ms must be > 0, got " + leaseTimeoutMs);
        }
        if (leaseHardTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "lease.hardTimeout.ms must be > 0, got " + leaseHardTimeoutMs);
        }
        if (leaseHardTimeoutMs < leaseTimeoutMs) {
            throw new IllegalArgumentException(
                    "lease.hardTimeout.ms must be >= lease.timeout.ms");
        }
        if (shutdownGracePeriodMs <= 0) {
            throw new IllegalArgumentException(
                    "shutdown.gracePeriod.ms must be > 0, got " + shutdownGracePeriodMs);
        }
        if (retryMaxAttempts < 0) {
            throw new IllegalArgumentException(
                    "retry.maxAttempts must be >= 0, got " + retryMaxAttempts);
        }
        if (retryBaseDelayMs <= 0) {
            throw new IllegalArgumentException(
                    "retry.baseDelay.ms must be > 0, got " + retryBaseDelayMs);
        }
        if (retryMaxDelayMs < retryBaseDelayMs) {
            throw new IllegalArgumentException(
                    "retry.maxDelay.ms must be >= retry.baseDelay.ms");
        }
        if (recoveryMaxConcurrentRebuilds <= 0) {
            throw new IllegalArgumentException(
                    "recovery.maxConcurrentRebuilds must be > 0, got " + recoveryMaxConcurrentRebuilds);
        }
        Objects.requireNonNull(serviceIdentity, "serviceIdentity is required");
        if (serviceIdentity.isBlank()) {
            throw new IllegalArgumentException("serviceIdentity must not be blank");
        }
        Objects.requireNonNull(instanceIdentifier, "instanceIdentifier is required");
        if (instanceIdentifier.isBlank()) {
            throw new IllegalArgumentException("instanceIdentifier must not be blank");
        }
        Objects.requireNonNull(kafkaProperties, "kafkaProperties is required");
    }

    /**
     * Build the transactional.id for a given pool slot index.
     *
     * @param slotIndex zero-based slot index within the pool
     * @return deterministic transactional.id string
     */
    public String buildTransactionalId(int slotIndex) {
        return serviceIdentity + "-" + instanceIdentifier + "-" + slotIndex;
    }

    // --- Getters ---

    public int getPoolSize() { return poolSize; }
    public int getMinHealthyProducers() { return minHealthyProducers; }
    public long getLeaseTimeoutMs() { return leaseTimeoutMs; }
    public long getLeaseHardTimeoutMs() { return leaseHardTimeoutMs; }
    public long getShutdownGracePeriodMs() { return shutdownGracePeriodMs; }
    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public long getRetryBaseDelayMs() { return retryBaseDelayMs; }
    public long getRetryMaxDelayMs() { return retryMaxDelayMs; }
    public int getRecoveryMaxConcurrentRebuilds() { return recoveryMaxConcurrentRebuilds; }
    public String getServiceIdentity() { return serviceIdentity; }
    public String getInstanceIdentifier() { return instanceIdentifier; }

    /** Returns a defensive copy of the Kafka producer properties. */
    public Properties getKafkaProperties() {
        Properties copy = new Properties();
        copy.putAll(kafkaProperties);
        return copy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int poolSize = 5;
        private int minHealthyProducers = 1;
        private long leaseTimeoutMs = 5_000L;
        private long leaseHardTimeoutMs = 30_000L;
        private long shutdownGracePeriodMs = 10_000L;
        private int retryMaxAttempts = 3;
        private long retryBaseDelayMs = 100L;
        private long retryMaxDelayMs = 5_000L;
        private int recoveryMaxConcurrentRebuilds = 1;
        private String serviceIdentity;
        private String instanceIdentifier;
        private Properties kafkaProperties = new Properties();

        public Builder poolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        public Builder minHealthyProducers(int min) {
            this.minHealthyProducers = min;
            return this;
        }

        public Builder leaseTimeoutMs(long ms) {
            this.leaseTimeoutMs = ms;
            return this;
        }

        public Builder leaseHardTimeoutMs(long ms) {
            this.leaseHardTimeoutMs = ms;
            return this;
        }

        public Builder shutdownGracePeriodMs(long ms) {
            this.shutdownGracePeriodMs = ms;
            return this;
        }

        public Builder retryMaxAttempts(int max) {
            this.retryMaxAttempts = max;
            return this;
        }

        public Builder retryBaseDelayMs(long ms) {
            this.retryBaseDelayMs = ms;
            return this;
        }

        public Builder retryMaxDelayMs(long ms) {
            this.retryMaxDelayMs = ms;
            return this;
        }

        public Builder recoveryMaxConcurrentRebuilds(int max) {
            this.recoveryMaxConcurrentRebuilds = max;
            return this;
        }

        public Builder serviceIdentity(String serviceIdentity) {
            this.serviceIdentity = serviceIdentity;
            return this;
        }

        public Builder instanceIdentifier(String instanceIdentifier) {
            this.instanceIdentifier = instanceIdentifier;
            return this;
        }

        public Builder kafkaProperties(Properties props) {
            this.kafkaProperties = Objects.requireNonNull(props);
            return this;
        }

        public PoolConfig build() {
            return new PoolConfig(this);
        }
    }
}
