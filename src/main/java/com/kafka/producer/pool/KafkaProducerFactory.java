package com.kafka.producer.pool;

import org.apache.kafka.clients.producer.KafkaProducer;

import java.util.Properties;

/**
 * Factory for creating {@link KafkaProducer} instances.
 *
 * <p>Abstracting construction enables unit-testing the pool without a real Kafka broker.
 */
public interface KafkaProducerFactory {

    /**
     * Create a new {@link KafkaProducer} configured with the supplied properties.
     *
     * @param props Kafka producer configuration (must include serializer and transactional.id)
     * @return a new, unclosed KafkaProducer instance
     */
    KafkaProducer<byte[], byte[]> create(Properties props);
}
