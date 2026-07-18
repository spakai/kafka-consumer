package com.kafka.producer.chaos.scenarios;

import java.util.Map;

public final class NetworkPartitionScenario implements ChaosScenario {
    private final boolean oneBroker;
    private int brokerId;
    private boolean partitioned;
    private boolean healed;

    public NetworkPartitionScenario(boolean oneBroker) {
        this.oneBroker = oneBroker;
    }

    @Override
    public void onStart(ScenarioContext context) {
        if (oneBroker) {
            brokerId = context.inspector().selectLeaderBroker(context.initialCluster());
            context.event("broker-selected", "confirmed", "network target",
                    Map.of("brokerId", brokerId));
        }
    }

    @Override
    public void onTick(ScenarioContext context, long elapsedSecond) throws Exception {
        long faultAt = context.config().faultAt().toSeconds();
        long healAt = faultAt + context.config().faultDuration().toSeconds();
        if (!partitioned && elapsedSecond >= faultAt) {
            if (oneBroker) {
                context.controller().partitionProducerFromBroker(brokerId);
            } else {
                context.controller().partitionProducerFromCluster();
            }
            partitioned = true;
        }
        if (partitioned && !healed && elapsedSecond >= healAt) {
            context.controller().healNetwork();
            healed = true;
        }
    }

    @Override
    public void onFinish(ScenarioContext context) throws Exception {
        if (partitioned && !healed) {
            context.controller().healNetwork();
            healed = true;
        }
    }
}
