package com.kafka.producer.chaos.scenarios;

/**
 * Exercises the producer's ambiguous-commit path using an external proxy or
 * fault hook. The hook must drop the response after the commit request can
 * have reached Kafka, then be removed by the common network heal command.
 */
public final class CommitResponsePartitionScenario implements ChaosScenario {
    private boolean faultInjected;
    private boolean healed;

    @Override
    public void onTick(ScenarioContext context, long elapsedSecond) throws Exception {
        long faultAt = context.config().faultAt().toSeconds();
        long healAt = faultAt + context.config().faultDuration().toSeconds();
        if (!faultInjected && elapsedSecond >= faultAt) {
            context.controller().partitionCommitResponse();
            faultInjected = true;
        }
        if (faultInjected && !healed && elapsedSecond >= healAt) {
            context.controller().healNetwork();
            healed = true;
        }
    }

    @Override
    public void onStart(ScenarioContext context) {
        context.event("commit-response-fault", "scheduled",
                "external hook will interrupt commit responses");
    }

    @Override
    public void onFinish(ScenarioContext context) throws Exception {
        if (faultInjected && !healed) {
            context.controller().healNetwork();
            healed = true;
        }
    }
}
