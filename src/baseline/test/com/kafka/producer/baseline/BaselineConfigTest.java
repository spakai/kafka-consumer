package com.kafka.producer.baseline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineConfigTest {
    @Test
    void parsesCommandLineConfiguration() {
        BaselineConfig config = BaselineConfig.fromArgs(new String[] {
                "--scenario=B-04", "--implementation=pool", "--producerCount=4",
                "--threads=16", "--recordsPerTransaction=10", "--durationSec=3"
        });

        assertEquals("B-04", config.scenario());
        assertEquals(4, config.producerCount());
        assertEquals(16, config.threads());
        assertEquals(3, config.duration().toSeconds());
        assertEquals(10, config.recordsPerTransaction());
        assertTrue(config.implementations().contains("pool"));
    }

    @Test
    void selectsReplicatedDefaultsForThreeBrokerTopology() {
        BaselineConfig config = BaselineConfig.fromArgs(new String[] {
                "--topology=three-broker", "--implementation=pool"
        });

        assertEquals("baseline-compare-replicated", config.topic());
        assertEquals("localhost:19092,localhost:29092,localhost:39092",
                config.bootstrapServers());
    }
}
