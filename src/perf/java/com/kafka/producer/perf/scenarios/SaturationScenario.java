package com.kafka.producer.perf.scenarios;

import com.kafka.producer.perf.LatencyRecorder;
import com.kafka.producer.perf.ResultWriter;
import com.kafka.producer.perf.ScenarioConfig;
import com.kafka.producer.pool.TransactionalProducerPool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SaturationScenario {
    private static final String SCENARIO = "S-05";
    private static final int POOL_SIZE = 4;

    public void run(ScenarioConfig config) throws Exception {
        int[] threadCounts = {8, 16, 32};
        List<String> csv = new ArrayList<>();
        csv.add("scenario,pool_size,threads,tx_per_sec,lease_timeout_rate,saturation_rate,p95_lease_ms,p99_tx_ms");

        for (int threads : threadCounts) {
            LatencyRecorder txLatency = new LatencyRecorder();
            LatencyRecorder leaseLatency = new LatencyRecorder();
            TransactionalProducerPool pool = ScenarioSupport.createPool(config, POOL_SIZE);
            try {
                ScenarioSupport.ScenarioStats stats = ScenarioSupport.runWorkers(
                        threads,
                        config.duration(),
                        ScenarioSupport.transactionalOperation(
                                pool,
                                config.topic(),
                                ScenarioSupport.payload(1024),
                                10,
                                leaseLatency),
                        txLatency);

                double totalAttempts = stats.successfulTx + stats.leaseTimeouts + stats.saturations + stats.failures;
                double leaseTimeoutRate = totalAttempts == 0 ? 0 : stats.leaseTimeouts / totalAttempts;
                double saturationRate = totalAttempts == 0 ? 0 : stats.saturations / totalAttempts;

                csv.add(String.format("%s,%d,%d,%.2f,%.4f,%.4f,%.3f,%.3f",
                        SCENARIO,
                        POOL_SIZE,
                        threads,
                        stats.txPerSec(),
                        leaseTimeoutRate,
                        saturationRate,
                        leaseLatency.p95Millis(),
                        txLatency.p99Millis()));
            } finally {
                pool.shutdown();
            }
        }

        Path csvFile = ResultWriter.writeCsv(config.resultsDir(), SCENARIO, csv);
        ResultWriter.appendSummary(config.resultsDir(), SCENARIO, List.of(
                "- pool size: " + POOL_SIZE,
                "- thread counts: 8, 16, 32",
                "- lease timeout configured: " + config.leaseTimeoutMs() + "ms",
                "- CSV: `" + csvFile.getFileName() + "`"));
    }
}
