package com.kafka.producer.chaos;

import java.time.Instant;
import java.util.Map;

public record ChaosEvent(
        Instant timestamp,
        String type,
        String outcome,
        String detail,
        Map<String, Object> attributes) {

    public static ChaosEvent of(String type, String outcome, String detail) {
        return new ChaosEvent(Instant.now(), type, outcome, detail, Map.of());
    }

    public static ChaosEvent of(
            String type, String outcome, String detail, Map<String, Object> attributes) {
        return new ChaosEvent(Instant.now(), type, outcome, detail, Map.copyOf(attributes));
    }
}
