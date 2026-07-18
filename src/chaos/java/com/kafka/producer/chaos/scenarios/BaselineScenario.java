package com.kafka.producer.chaos.scenarios;

public final class BaselineScenario implements ChaosScenario {
    @Override
    public void onStart(ScenarioContext context) {
        context.event("baseline", "started", context.config().scenario());
    }

    @Override
    public void onTick(ScenarioContext context, long elapsedSecond) {
        // No fault injection.
    }

    @Override
    public void onFinish(ScenarioContext context) {
        context.event("baseline", "completed", context.config().scenario());
    }
}
