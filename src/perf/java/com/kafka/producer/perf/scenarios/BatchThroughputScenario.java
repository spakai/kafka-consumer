package com.kafka.producer.perf.scenarios;

import com.kafka.producer.perf.LatencyRecorder;
import com.kafka.producer.perf.ResultWriter;
import com.kafka.producer.perf.ScenarioConfig;
import com.kafka.producer.pool.TransactionalProducerPool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BatchThroughputScenario {
    private static final String SCENARIO = "S-03";
    private static final int POOL_SIZE = 4;
    private static final int THREADS = 4;

    public void run(ScenarioConfig config) throws Exception {
        int[] batchSizes = {1, 10, 50, 100};
        List<String> csv = new ArrayList<>();
        csv.add("scenario,pool_size,threads,records_per_tx,tx_per_sec,msg_per_sec,p95_tx_ms");

        double bestMsgPerSec = 0;
        int bestBatch = 1;

        for (int batchSize : batchSizes) {
            LatencyRecorder txLatency = new LatencyRecorder();
            LatencyRecorder leaseLatency = new LatencyRecorder();
            TransactionalProducerPool pool = ScenarioSupport.createPool(config, POOL_SIZE);
            try {
                ScenarioSupport.ScenarioStats stats = ScenarioSupport.runWorkers(
                        THREADS,
                        config.duration(),
                        ScenarioSupport.transactionalOperation(
                                pool,
                                config.topic(),
                                ScenarioSupport.payload(1024),
                                batchSize,
                                leaseLatency),
                        txLatency);

                if (stats.msgPerSec() > bestMsgPerSec) {
                    bestMsgPerSec = stats.msgPerSec();
                    bestBatch = batchSize;
                }

                csv.add(String.format("%s,%d,%d,%d,%.2f,%.2f,%.3f",
                        SCENARIO,
                        POOL_SIZE,
                        THREADS,
                        batchSize,
                        stats.txPerSec(),
                        stats.msgPerSec(),
                        txLatency.p95Millis()));
            } finally {
                pool.shutdown();
            }
        }

        Path csvFile = ResultWriter.writeCsv(config.resultsDir(), SCENARIO, csv);
        ResultWriter.appendSummary(config.resultsDir(), SCENARIO, List.of(
                "- pool size: " + POOL_SIZE,
                "- threads: " + THREADS,
                "- optimal batch size (peak msg/s): " + bestBatch,
                "- peak msg/s: " + String.format("%.2f", bestMsgPerSec),
                "- CSV: `" + csvFile.getFileName() + "`"));
    }
}
