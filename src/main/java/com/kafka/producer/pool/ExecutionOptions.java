package com.kafka.producer.pool;

import java.util.Objects;

/**
 * Per-call options for {@link TransactionalProducerPool#executeInTransaction}.
 *
 * <p>Values here override the pool-level defaults from {@link PoolConfig} for a single call.
 */
public final class ExecutionOptions {

    /** Use pool-default retry attempts. */
    public static final int USE_POOL_DEFAULT = -1;
    /** Use pool-default lease timeout. */
    public static final long USE_POOL_DEFAULT_TIMEOUT = -1L;

    private final long leaseTimeoutMs;
    private final int maxRetryAttempts;
    private final String correlationId;

    private ExecutionOptions(Builder builder) {
        this.leaseTimeoutMs = builder.leaseTimeoutMs;
        this.maxRetryAttempts = builder.maxRetryAttempts;
        this.correlationId = builder.correlationId;
    }

    /**
     * Create options with all pool defaults.
     *
     * @return default options instance
     */
    public static ExecutionOptions defaults() {
        return builder().build();
    }

    /**
     * Lease acquisition timeout in milliseconds, or {@link #USE_POOL_DEFAULT_TIMEOUT}
     * to use the pool-level default.
     */
    public long getLeaseTimeoutMs() {
        return leaseTimeoutMs;
    }

    /**
     * Maximum number of retry attempts for retriable errors, or {@link #USE_POOL_DEFAULT}
     * to use the pool-level default.
     */
    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    /**
     * Optional correlation ID for structured logging and tracing.
     * May be {@code null}.
     */
    public String getCorrelationId() {
        return correlationId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long leaseTimeoutMs = USE_POOL_DEFAULT_TIMEOUT;
        private int maxRetryAttempts = USE_POOL_DEFAULT;
        private String correlationId;

        public Builder leaseTimeoutMs(long ms) {
            this.leaseTimeoutMs = ms;
            return this;
        }

        public Builder maxRetryAttempts(int attempts) {
            this.maxRetryAttempts = attempts;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public ExecutionOptions build() {
            return new ExecutionOptions(this);
        }
    }
}
