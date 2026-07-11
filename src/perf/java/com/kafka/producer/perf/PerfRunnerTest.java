package com.kafka.producer.perf;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PerfRunnerTest {

    @Test
    void runConfiguredScenario() throws Exception {
        String scenario = System.getProperty("scenario");
        Assumptions.assumeTrue(scenario != null && !scenario.isBlank(), "No -Dscenario provided");
        PerfRunner.main(new String[]{"--scenario", scenario});
    }
}
