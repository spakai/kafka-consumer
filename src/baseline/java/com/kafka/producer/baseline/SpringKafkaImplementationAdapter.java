package com.kafka.producer.baseline;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;

public final class SpringKafkaImplementationAdapter implements ImplementationAdapter {
    private final DefaultKafkaProducerFactory<byte[], byte[]> producerFactory;
    private final KafkaTemplate<byte[], byte[]> template;

    public SpringKafkaImplementationAdapter(BaselineConfig config, String runId) {
        producerFactory = new DefaultKafkaProducerFactory<>(ProducerProperties.asMap(config));
        producerFactory.setTransactionIdPrefix("baseline-spring-" + runId + "-");
        template = new KafkaTemplate<>(producerFactory);
    }

    @Override
    public String name() {
        return "spring-kafka";
    }

    @Override
    public void executeInTransaction(List<ProducerRecord<byte[], byte[]>> records) {
        template.executeInTransaction(operations -> {
            for (ProducerRecord<byte[], byte[]> record : records) {
                operations.send(record).join();
            }
            return null;
        });
    }

    @Override
    public Map<String, Number> metrics() {
        return Map.of("producer_factory_cache_config", 0);
    }

    @Override
    public void close() {
        producerFactory.destroy();
    }
}
