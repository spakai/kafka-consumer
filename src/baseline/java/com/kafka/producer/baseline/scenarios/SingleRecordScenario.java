package com.kafka.producer.baseline.scenarios;

import com.kafka.producer.baseline.*;

public final class SingleRecordScenario implements BaselineScenario {
    public BenchmarkResult run(BaselineConfig config, ImplementationAdapter adapter,
                               BenchmarkEngine engine) throws Exception {
        if (config.recordsPerTransaction() != 1) {
            throw new IllegalArgumentException("B-01 requires recordsPerTransaction=1");
        }
        return engine.run(config, adapter);
    }
}
