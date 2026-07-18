package com.kafka.producer.baseline.scenarios;

import com.kafka.producer.baseline.*;

public final class BrokerRecoveryScenario implements BaselineScenario {
    public BenchmarkResult run(BaselineConfig config, ImplementationAdapter adapter,
                               BenchmarkEngine engine) throws Exception {
        if (!config.topology().equals("three-broker")) {
            throw new IllegalArgumentException("B-06 requires topology=three-broker");
        }
        return engine.run(config, adapter);
    }
}
