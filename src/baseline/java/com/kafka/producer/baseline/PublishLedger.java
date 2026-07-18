package com.kafka.producer.baseline;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

final class PublishLedger {
    enum Outcome { COMMITTED, FAILED, AMBIGUOUS }

    record Entry(String publishId, String key, long sequence, int expectedRecords, Outcome outcome) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    void record(String publishId, String key, long sequence, int expectedRecords, Outcome outcome) {
        entries.put(publishId, new Entry(publishId, key, sequence, expectedRecords, outcome));
    }

    Collection<Entry> entries() {
        return entries.values();
    }
}
