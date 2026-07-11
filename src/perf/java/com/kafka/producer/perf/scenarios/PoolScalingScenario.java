package com.kafka.producer.perf.scenarios;

import com.kafka.producer.perf.LatencyRecorder;
import com.kafka.producer.perf.ResultWriter;
import com.kafka.producer.perf.ScenarioConfig;
import com.kafka.producer.pool.TransactionalProducerPool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PoolScalingScenario {
    private static final String SCENARIO = "S-04";

    public void run(ScenarioConfig config) throws Exception {
        int[] poolSizes = {1, 2, 4, 8, 16};
        List<String> csv = new ArrayList<>();
        csv.add("scenario,pool_size,threads,msg_per_sec,scaling_efficiency,p95_lease_ms");

        double baseline = 0;
        for (int poolSize : poolSizes) {
            LatencyRecorder txLatency = new LatencyRecorder();
            LatencyRecorder leaseLatency = new LatencyRecorder();
            TransactionalProducerPool pool = ScenarioSupport.createPool(config, poolSize);
            try {
                ScenarioSupport.ScenarioStats stats = ScenarioSupport.runWorkers(
                        poolSize,
                        config.duration(),
                        ScenarioSupport.transactionalOperation(
                                pool,
                                config.topic(),
                                ScenarioSupport.payload(1024),
                                10,
                                leaseLatency),
                        txLatency);

                if (poolSize == 1) {
                    baseline = stats.msgPerSec();
                }
                double ideal = baseline * poolSize;
                double efficiency = ideal > 0 ? stats.msgPerSec() / ideal : 0;
                csv.add(String.format("%s,%d,%d,%.2f,%.3f,%.3f",
                        SCENARIO,
                        poolSize,
                        poolSize,
                        stats.msgPerSec(),
                        efficiency,
                        leaseLatency.p95Millis()));
            } finally {
                pool.shutdown();
            }
        }

        Path csvFile = ResultWriter.writeCsv(config.resultsDir(), SCENARIO, csv);
        ResultWriter.appendSummary(config.resultsDir(), SCENARIO, List.of(
                "- pool sizes tested: 1, 2, 4, 8, 16",
                "- records/tx: 10",
                "- message size: 1KB",
                "- CSV: `" + csvFile.getFileName() + "`"));
    }
}
