package com.kafka.producer.baseline.scenarios;

import com.kafka.producer.baseline.BaselineConfig;
import com.kafka.producer.baseline.BenchmarkEngine;
import com.kafka.producer.baseline.BenchmarkResult;
import com.kafka.producer.baseline.ImplementationAdapter;

public interface BaselineScenario {
    BenchmarkResult run(BaselineConfig config, ImplementationAdapter adapter,
                        BenchmarkEngine engine) throws Exception;
}
