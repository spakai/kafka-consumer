package com.kafka.producer.chaos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencyWindowTest {

    @Test
    void returnsBoundedPercentilesAndResetsWindow() {
        LatencyWindow window = new LatencyWindow();
        window.record(500_000);
        window.record(4_000_000);
        window.record(80_000_000);
        window.record(450_000_000);

        LatencyWindow.Snapshot snapshot = window.snapshotAndReset();

        assertEquals(5.0, snapshot.percentileMillis(50));
        assertEquals(500.0, snapshot.percentileMillis(95));
        assertEquals(0.0, window.snapshotAndReset().percentileMillis(95));
    }
}
