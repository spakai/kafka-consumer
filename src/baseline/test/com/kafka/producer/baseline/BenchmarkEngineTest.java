package com.kafka.producer.baseline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkEngineTest {
    @Test
    void calculatesNearestRankPercentilesInMilliseconds() {
        List<Long> samples = List.of(
                1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L);

        assertEquals(3.0, BenchmarkEngine.percentile(samples, .50));
        assertEquals(5.0, BenchmarkEngine.percentile(samples, .95));
        assertEquals(5.0, BenchmarkEngine.percentile(samples, .99));
    }

    @Test
    void emptyPercentileIsZero() {
        assertEquals(0.0, BenchmarkEngine.percentile(List.of(), .95));
    }
}
