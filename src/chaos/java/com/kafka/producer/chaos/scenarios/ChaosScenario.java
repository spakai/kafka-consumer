package com.kafka.producer.chaos.scenarios;

public interface ChaosScenario {
    void onStart(ScenarioContext context) throws Exception;

    void onTick(ScenarioContext context, long elapsedSecond) throws Exception;

    void onFinish(ScenarioContext context) throws Exception;
}
