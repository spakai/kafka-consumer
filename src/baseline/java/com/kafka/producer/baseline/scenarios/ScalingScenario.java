package com.kafka.producer.baseline.scenarios;

import com.kafka.producer.baseline.*;

public final class ScalingScenario implements BaselineScenario {
    public BenchmarkResult run(BaselineConfig config, ImplementationAdapter adapter,
                               BenchmarkEngine engine) throws Exception {
        if (config.threads() != config.producerCount()) {
            throw new IllegalArgumentException("B-03 requires threads == producerCount");
        }
        return engine.run(config, adapter);
    }
}
