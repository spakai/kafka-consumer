package com.kafka.producer.chaos.scenarios;

public final class FlappingNetworkScenario implements ChaosScenario {
    private static final long PARTITION_SECONDS = 10;
    private static final long HEALTHY_SECONDS = 20;
    private static final int MAX_CYCLES = 6;
    private boolean partitioned;
    private int completedCycles;
    private long nextTransitionSecond;

    @Override
    public void onStart(ScenarioContext context) {
        nextTransitionSecond = context.config().faultAt().toSeconds();
        context.event("network-flapping", "scheduled", "six 10s/20s cycles");
    }

    @Override
    public void onTick(ScenarioContext context, long elapsedSecond) throws Exception {
        if (elapsedSecond < nextTransitionSecond || completedCycles >= MAX_CYCLES) {
            return;
        }
        if (!partitioned) {
            context.controller().partitionProducerFromCluster();
            partitioned = true;
            nextTransitionSecond = elapsedSecond + PARTITION_SECONDS;
        } else {
            context.controller().healNetwork();
            partitioned = false;
            completedCycles++;
            nextTransitionSecond = elapsedSecond + HEALTHY_SECONDS;
        }
    }

    @Override
    public void onFinish(ScenarioContext context) throws Exception {
        if (partitioned) {
            context.controller().healNetwork();
            partitioned = false;
        }
    }
}
