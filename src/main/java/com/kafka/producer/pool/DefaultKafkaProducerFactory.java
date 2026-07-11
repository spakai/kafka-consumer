package com.kafka.producer.pool;

import org.apache.kafka.clients.producer.KafkaProducer;

import java.util.Properties;

/**
 * Production {@link KafkaProducerFactory} that delegates directly to
 * {@code new KafkaProducer<>(props)}.
 */
public final class DefaultKafkaProducerFactory implements KafkaProducerFactory {

    @Override
    public KafkaProducer<byte[], byte[]> create(Properties props) {
        return new KafkaProducer<>(props);
    }
}
