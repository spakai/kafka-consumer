package com.kafka.producer.baseline.scenarios;

import com.kafka.producer.baseline.*;

public final class SustainedLoadScenario implements BaselineScenario {
    public BenchmarkResult run(BaselineConfig config, ImplementationAdapter adapter,
                               BenchmarkEngine engine) throws Exception {
        return engine.run(config, adapter);
    }
}
