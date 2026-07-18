package com.kafka.producer.baseline;

import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.List;
import java.util.Map;

public interface ImplementationAdapter extends AutoCloseable {
    String name();

    void executeInTransaction(List<ProducerRecord<byte[], byte[]>> records) throws Exception;

    default Map<String, Number> metrics() {
        return Map.of();
    }

    @Override
    void close();
}
