package com.kafka.producer.perf.scenarios;

import com.kafka.producer.perf.LatencyRecorder;
import com.kafka.producer.perf.ResultWriter;
import com.kafka.producer.perf.ScenarioConfig;
import com.kafka.producer.pool.TransactionalProducerPool;
import org.apache.kafka.clients.producer.KafkaProducer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BaselineThroughputScenario {
    private static final String MODE_TRANSACTIONAL = "transactional";
    private static final String MODE_NON_TRANSACTIONAL = "non-transactional";

    public void run(ScenarioConfig config, boolean transactional) throws Exception {
        LatencyRecorder txLatency = new LatencyRecorder();
        LatencyRecorder leaseLatency = new LatencyRecorder();

        ScenarioSupport.ScenarioStats stats;
        if (transactional) {
            TransactionalProducerPool pool = ScenarioSupport.createPool(config, 1);
            try {
                stats = ScenarioSupport.runWorkers(
                        1,
                        config.duration(),
                        ScenarioSupport.transactionalOperation(
                                pool,
                                config.topic(),
                                ScenarioSupport.payload(config.recordSizeBytes()),
                                1,
                                leaseLatency),
                        txLatency);
            } finally {
                pool.shutdown();
            }
        } else {
            KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(ScenarioSupport.kafkaProps(config));
            try {
                stats = ScenarioSupport.runWorkers(
                        1,
                        config.duration(),
                        ScenarioSupport.nonTransactionalOperation(
                                producer,
                                config.topic(),
                                ScenarioSupport.payload(config.recordSizeBytes())),
                        txLatency);
            } finally {
                producer.close();
            }
        }

        double mbps = (stats.msgPerSec() * config.recordSizeBytes()) / (1024.0 * 1024.0);
        String scenario = transactional ? "S-02" : "S-01";
        List<String> csv = new ArrayList<>();
        csv.add("scenario,mode,record_size_bytes,msg_per_sec,mb_per_sec,p50_ms,p95_ms,p99_ms,lease_p95_ms");
        csv.add(String.format(
                "%s,%s,%d,%.2f,%.2f,%.3f,%.3f,%.3f,%.3f",
                scenario,
                transactional ? MODE_TRANSACTIONAL : MODE_NON_TRANSACTIONAL,
                config.recordSizeBytes(),
                stats.msgPerSec(),
                mbps,
                txLatency.p50Millis(),
                txLatency.p95Millis(),
                txLatency.p99Millis(),
                leaseLatency.p95Millis()));

        Path csvFile = ResultWriter.writeCsv(config.resultsDir(), scenario, csv);
        ResultWriter.appendSummary(config.resultsDir(), scenario, List.of(
                "- mode: " + (transactional ? MODE_TRANSACTIONAL : MODE_NON_TRANSACTIONAL),
                "- msg/s: " + String.format("%.2f", stats.msgPerSec()),
                "- MB/s: " + String.format("%.2f", mbps),
                "- p95 latency (ms): " + String.format("%.3f", txLatency.p95Millis()),
                "- CSV: `" + csvFile.getFileName() + "`"));
    }
}
