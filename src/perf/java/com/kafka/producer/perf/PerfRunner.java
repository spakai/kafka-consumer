package com.kafka.producer.perf;

import com.kafka.producer.perf.scenarios.BaselineThroughputScenario;
import com.kafka.producer.perf.scenarios.BatchThroughputScenario;
import com.kafka.producer.perf.scenarios.PoolScalingScenario;
import com.kafka.producer.perf.scenarios.RecoveryLatencyScenario;
import com.kafka.producer.perf.scenarios.SaturationScenario;
import com.kafka.producer.perf.scenarios.SoakScenario;
import org.openjdk.jmh.Main;

public final class PerfRunner {

    public static void main(String[] args) throws Exception {
        ScenarioConfig config = ScenarioConfig.fromArgs(args);
        switch (config.scenario()) {
            case "S-01" -> new BaselineThroughputScenario().run(config, false);
            case "S-02" -> new BaselineThroughputScenario().run(config, true);
            case "S-03" -> new BatchThroughputScenario().run(config);
            case "S-04" -> new PoolScalingScenario().run(config);
            case "S-05" -> new SaturationScenario().run(config);
            case "S-06" -> new SoakScenario().run(config);
            case "S-07" -> new RecoveryLatencyScenario().run(config);
            case "S-08" -> Main.main(new String[]{"com.kafka.producer.perf.benchmarks.ExecuteInTransactionBenchmark"});
            default -> throw new IllegalArgumentException("Unknown scenario: " + config.scenario());
        }
    }
}
