package com.kafka.producer.baseline;

import com.kafka.producer.pool.ExecutionOptions;
import com.kafka.producer.pool.PoolConfig;
import com.kafka.producer.pool.TransactionalProducerPool;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.List;
import java.util.Map;

public final class PoolImplementationAdapter implements ImplementationAdapter {
    private final TransactionalProducerPool pool;

    public PoolImplementationAdapter(BaselineConfig config, String runId) {
        PoolConfig poolConfig = PoolConfig.builder()
                .poolSize(config.producerCount())
                .minHealthyProducers(config.producerCount())
                .leaseTimeoutMs(config.acquisitionTimeoutMs())
                .leaseHardTimeoutMs(120_000)
                .shutdownGracePeriodMs(30_000)
                .retryMaxAttempts(0)
                .retryBaseDelayMs(20)
                .retryMaxDelayMs(500)
                .recoveryMaxConcurrentRebuilds(Math.max(1, config.producerCount() / 2))
                .serviceIdentity("baseline-pool")
                .instanceIdentifier(runId)
                .kafkaProperties(ProducerProperties.asProperties(config))
                .build();
        pool = TransactionalProducerPool.create(poolConfig);
        pool.initialize();
    }

    @Override
    public String name() {
        return "pool";
    }

    @Override
    public void executeInTransaction(List<ProducerRecord<byte[], byte[]>> records) {
        pool.executeInTransaction(lease -> {
            for (ProducerRecord<byte[], byte[]> record : records) {
                lease.send(record).get();
            }
            return null;
        }, ExecutionOptions.builder().maxRetryAttempts(0).build());
    }

    @Override
    public Map<String, Number> metrics() {
        return Map.of(
                "active_producer_count", pool.getLeasedCount(),
                "producer_count", pool.getTotalCount());
    }

    @Override
    public void close() {
        pool.shutdown();
    }
}
