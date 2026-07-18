package com.kafka.producer.baseline;

import com.kafka.producer.baseline.scenarios.*;

public final class BaselineRunner {
    private BaselineRunner() {}

    public static void main(String[] args) throws Exception {
        BaselineConfig config = args.length == 0
                ? BaselineConfig.fromSystemProperties() : BaselineConfig.fromArgs(args);
        if (config.implementations().size() != 1) {
            throw new IllegalArgumentException(
                    "Fairness requires one implementation per JVM. Run once with "
                    + "-Dimplementation=pool and once with -Dimplementation=spring-kafka "
                    + "using the same runNumber and configuration.");
        }
        String implementation = config.implementations().iterator().next();
        BenchmarkEngine engine = new BenchmarkEngine();
        try (ImplementationAdapter adapter = adapter(implementation, config)) {
            BenchmarkResult result = scenario(config.scenario()).run(config, adapter, engine);
            new ResultWriter().write(config, result);
            System.out.printf("%s %s: %.2f records/s, p95 %.2f ms, correctness %s%n",
                    result.scenario(), result.implementation(), result.recordsPerSecond(),
                    result.p95Ms(), result.correctness().passed() ? "PASS" : "FAIL");
            if (!result.correctness().passed()) {
                throw new IllegalStateException("correctness verification failed: "
                        + result.correctness().issues());
            }
        }
    }

    private static ImplementationAdapter adapter(String name, BaselineConfig config) {
        String runId = config.scenario().toLowerCase() + "-" + config.runNumber()
                + "-" + Long.toUnsignedString(config.seed());
        return switch (name) {
            case "pool" -> new PoolImplementationAdapter(config, runId);
            case "spring-kafka" -> new SpringKafkaImplementationAdapter(config, runId);
            default -> throw new IllegalArgumentException("Unknown implementation: " + name);
        };
    }

    private static BaselineScenario scenario(String name) {
        return switch (name) {
            case "B-01" -> new SingleRecordScenario();
            case "B-02" -> new BatchScenario();
            case "B-03" -> new ScalingScenario();
            case "B-04" -> new OversubscriptionScenario();
            case "B-05" -> new SustainedLoadScenario();
            case "B-06" -> new BrokerRecoveryScenario();
            default -> throw new IllegalArgumentException("Unknown scenario: " + name);
        };
    }
}
