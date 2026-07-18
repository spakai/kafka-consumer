package com.kafka.producer.baseline;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class LoadWorker implements Callable<LoadWorker.Stats> {
    static final class Stats {
        long transactions;
        long records;
        long timeouts;
        long failures;
        long ambiguous;
        final List<Long> latencies = new ArrayList<>();
    }

    private final int worker;
    private final BaselineConfig config;
    private final String runId;
    private final ImplementationAdapter adapter;
    private final AtomicBoolean stop;
    private final AtomicLong sequences;
    private final PublishLedger ledger;
    private final boolean measured;

    LoadWorker(int worker, BaselineConfig config, String runId, ImplementationAdapter adapter,
               AtomicBoolean stop, AtomicLong sequences, PublishLedger ledger, boolean measured) {
        this.worker = worker;
        this.config = config;
        this.runId = runId;
        this.adapter = adapter;
        this.stop = stop;
        this.sequences = sequences;
        this.ledger = ledger;
        this.measured = measured;
    }

    @Override
    public Stats call() {
        Stats stats = new Stats();
        SplittableRandom random = new SplittableRandom(config.seed() + worker);
        while (!stop.get()) {
            long sequence = sequences.incrementAndGet();
            String publishId = runId + "-" + UUID.randomUUID();
            // One deterministic key per worker keeps per-key publication serial while
            // still distributing concurrent callers across topic partitions.
            String keyText = "key-" + worker;
            List<ProducerRecord<byte[], byte[]>> records =
                    records(publishId, keyText, sequence, random);
            long start = System.nanoTime();
            try {
                adapter.executeInTransaction(records);
                if (measured) {
                    stats.latencies.add(System.nanoTime() - start);
                    stats.transactions++;
                    stats.records += records.size();
                    ledger.record(publishId, keyText, sequence, records.size(),
                            PublishLedger.Outcome.COMMITTED);
                }
            } catch (Exception error) {
                if (!measured) {
                    continue;
                }
                Throwable cause = rootCause(error);
                if (cause instanceof TimeoutException
                        || cause instanceof java.util.concurrent.TimeoutException) {
                    stats.timeouts++;
                    stats.ambiguous++;
                    ledger.record(publishId, keyText, sequence, records.size(),
                            PublishLedger.Outcome.AMBIGUOUS);
                } else {
                    stats.failures++;
                    ledger.record(publishId, keyText, sequence, records.size(),
                            PublishLedger.Outcome.FAILED);
                }
            }
        }
        return stats;
    }

    private List<ProducerRecord<byte[], byte[]>> records(
            String publishId, String keyText, long sequence, SplittableRandom random) {
        byte[] key = keyText.getBytes(StandardCharsets.UTF_8);
        List<ProducerRecord<byte[], byte[]>> records =
                new ArrayList<>(config.recordsPerTransaction());
        for (int index = 0; index < config.recordsPerTransaction(); index++) {
            byte[] value = new byte[config.recordSize()];
            random.nextBytes(value);
            ByteBuffer.wrap(value).putLong(sequence).putInt(index);
            ProducerRecord<byte[], byte[]> record =
                    new ProducerRecord<>(config.topic(), key, value);
            record.headers()
                    .add(new RecordHeader("baseline.run-id", bytes(runId)))
                    .add(new RecordHeader("baseline.implementation", bytes(adapter.name())))
                    .add(new RecordHeader("publish.id", bytes(publishId)))
                    .add(new RecordHeader("publish.sequence", bytes(Long.toString(sequence))))
                    .add(new RecordHeader("publish.record-index", bytes(Integer.toString(index))));
            records.add(record);
        }
        return records;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
