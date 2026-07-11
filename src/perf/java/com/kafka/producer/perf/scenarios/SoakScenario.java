package com.kafka.producer.perf.scenarios;

import com.kafka.producer.perf.LatencyRecorder;
import com.kafka.producer.perf.ResultWriter;
import com.kafka.producer.perf.ScenarioConfig;
import com.kafka.producer.pool.TransactionalProducerPool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SoakScenario {
    private static final String SCENARIO = "S-06";
    private static final int POOL_SIZE = 8;
    private static final int THREADS = 8;

    public void run(ScenarioConfig config) throws Exception {
        TransactionalProducerPool pool = ScenarioSupport.createPool(config, POOL_SIZE);
        LatencyRecorder txLatency = new LatencyRecorder();
        LatencyRecorder leaseLatency = new LatencyRecorder();

        List<String> csv = new ArrayList<>();
        csv.add("scenario,second_window,msg_per_sec,heap_used_mb,p95_tx_ms,p95_lease_ms");

        long totalSeconds = config.duration().toSeconds();
        long sampleEvery = 30;
        long leaseTimeouts = 0;
        long saturations = 0;
        for (long second = sampleEvery; second <= totalSeconds; second += sampleEvery) {
            ScenarioSupport.ScenarioStats window = ScenarioSupport.runWorkers(
                    THREADS,
                    java.time.Duration.ofSeconds(sampleEvery),
                    ScenarioSupport.transactionalOperation(pool, config.topic(), ScenarioSupport.payload(1024), 10, leaseLatency),
                    txLatency);

            leaseTimeouts += window.leaseTimeouts;
            saturations += window.saturations;
            long usedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            csv.add(String.format("%s,%d,%.2f,%.2f,%.3f,%.3f",
                    SCENARIO,
                    second,
                    window.msgPerSec(),
                    usedHeap / (1024.0 * 1024.0),
                    txLatency.p95Millis(),
                    leaseLatency.p95Millis()));
        }

        pool.shutdown();

        Path csvFile = ResultWriter.writeCsv(config.resultsDir(), SCENARIO, csv);
        ResultWriter.appendSummary(config.resultsDir(), SCENARIO, List.of(
                "- pool size: " + POOL_SIZE,
                "- threads: " + THREADS,
                "- records/tx: 10",
                "- lease timeout count: " + leaseTimeouts,
                "- saturation exception count: " + saturations,
                "- CSV: `" + csvFile.getFileName() + "`"));
    }
}
