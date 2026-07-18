package com.kafka.producer.baseline;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CorrectnessVerifier {
    public record Report(boolean passed, int missingCommitted, int visibleFailed,
                         int duplicateRecords, int partialTransactions,
                         int orderingViolations, List<String> issues) {
        static Report notRun() {
            return new Report(false, 0, 0, 0, 0, 0, List.of("verification not run"));
        }
    }

    public Report verify(BaselineConfig config, String runId, PublishLedger ledger,
                         Map<TopicPartition, Long> startOffsets) {
        Map<String, Observed> observed = readCommitted(config, runId, startOffsets);
        List<String> issues = new ArrayList<>();
        int missing = 0;
        int visibleFailed = 0;
        int duplicates = 0;
        int partial = 0;
        Map<String, PublishLedger.Entry> entries = new HashMap<>();
        for (PublishLedger.Entry entry : ledger.entries()) {
            entries.put(entry.publishId(), entry);
            Observed actual = observed.get(entry.publishId());
            int count = actual == null ? 0 : actual.count;
            if (entry.outcome() == PublishLedger.Outcome.COMMITTED
                    && count != entry.expectedRecords()) {
                missing++;
                issues.add("committed publish " + entry.publishId() + " expected "
                        + entry.expectedRecords() + " records, observed " + count);
            }
            if (entry.outcome() == PublishLedger.Outcome.FAILED && count > 0) {
                visibleFailed++;
                issues.add("failed publish is visible: " + entry.publishId());
            }
            if (actual != null && actual.duplicateIndexes > 0) {
                duplicates++;
                issues.add("duplicate record index: " + entry.publishId());
            } else if (count > 0 && count < entry.expectedRecords()) {
                partial++;
                issues.add("partial transaction: " + entry.publishId());
            }
        }
        int ordering = orderingViolations(observed, entries, issues);
        return new Report(missing + visibleFailed + duplicates + partial + ordering == 0,
                missing, visibleFailed, duplicates, partial, ordering, List.copyOf(issues));
    }

    public Map<TopicPartition, Long> captureEndOffsets(BaselineConfig config) {
        try (var consumer = consumer(config)) {
            List<TopicPartition> partitions = partitions(consumer, config.topic());
            return Map.copyOf(consumer.endOffsets(partitions));
        }
    }

    private Map<String, Observed> readCommitted(
            BaselineConfig config, String runId, Map<TopicPartition, Long> startOffsets) {
        Map<String, Observed> result = new HashMap<>();
        try (var consumer = consumer(config)) {
            List<TopicPartition> partitions = partitions(consumer, config.topic());
            consumer.assign(partitions);
            for (TopicPartition partition : partitions) {
                consumer.seek(partition, startOffsets.getOrDefault(partition, 0L));
            }
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            while (!atEnd(consumer, ends)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (!runId.equals(header(record, "baseline.run-id"))) {
                        continue;
                    }
                    String publishId = header(record, "publish.id");
                    int index = Integer.parseInt(header(record, "publish.record-index"));
                    long sequence = Long.parseLong(header(record, "publish.sequence"));
                    String key = new String(record.key(), StandardCharsets.UTF_8);
                    Observed value = result.computeIfAbsent(publishId,
                            ignored -> new Observed(key, sequence));
                    value.count++;
                    value.firstOffset = Math.min(value.firstOffset, record.offset());
                    if (!value.indexes.add(index)) {
                        value.duplicateIndexes++;
                    }
                }
            }
        }
        return result;
    }

    private KafkaConsumer<byte[], byte[]> consumer(BaselineConfig config) {
        var properties = new java.util.Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "baseline-verifier-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(properties);
    }

    private static List<TopicPartition> partitions(
            KafkaConsumer<byte[], byte[]> consumer, String topic) {
        return consumer.partitionsFor(topic).stream()
                .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
    }

    private static boolean atEnd(KafkaConsumer<byte[], byte[]> consumer,
                                 Map<TopicPartition, Long> ends) {
        return ends.entrySet().stream()
                .allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue());
    }

    private static String header(org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> record,
                                 String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static int orderingViolations(Map<String, Observed> observed,
                                          Map<String, PublishLedger.Entry> entries,
                                          List<String> issues) {
        Map<String, List<Observed>> byKey = new HashMap<>();
        observed.forEach((id, value) -> {
            if (entries.containsKey(id)) {
                byKey.computeIfAbsent(value.key, ignored -> new ArrayList<>()).add(value);
            }
        });
        int count = 0;
        for (var entry : byKey.entrySet()) {
            long previous = Long.MIN_VALUE;
            entry.getValue().sort(Comparator.comparingLong(value -> value.firstOffset));
            for (Observed value : entry.getValue()) {
                if (value.sequence < previous) {
                    count++;
                    issues.add("sequence inversion for " + entry.getKey());
                }
                previous = value.sequence;
            }
        }
        return count;
    }

    private static final class Observed {
        final String key;
        final long sequence;
        final Set<Integer> indexes = new HashSet<>();
        int count;
        int duplicateIndexes;
        long firstOffset = Long.MAX_VALUE;

        Observed(String key, long sequence) {
            this.key = key;
            this.sequence = sequence;
        }
    }
}
