package com.kafka.producer.chaos;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class EventRecorder implements Consumer<ChaosEvent> {
    private final CopyOnWriteArrayList<ChaosEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void accept(ChaosEvent event) {
        events.add(event);
    }

    public List<ChaosEvent> snapshot() {
        return List.copyOf(events);
    }
}
