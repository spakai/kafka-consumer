package com.kafka.producer.baseline;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

final class ProducerProperties {
    private ProducerProperties() {}

    static Map<String, Object> asMap(BaselineConfig config) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.RETRIES_CONFIG, 3);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        properties.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, 60_000);
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        properties.put(ProducerConfig.BATCH_SIZE_CONFIG, 16_384);
        properties.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33_554_432L);
        properties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, config.compression());
        return properties;
    }

    static Properties asProperties(BaselineConfig config) {
        Properties properties = new Properties();
        properties.putAll(asMap(config));
        return properties;
    }
}
