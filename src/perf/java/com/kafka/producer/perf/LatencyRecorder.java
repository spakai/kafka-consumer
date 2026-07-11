package com.kafka.producer.perf;

import org.HdrHistogram.Histogram;

import java.util.concurrent.TimeUnit;

public final class LatencyRecorder {
    private final Histogram histogram;

    public LatencyRecorder() {
        this.histogram = new Histogram(TimeUnit.SECONDS.toNanos(30), 3);
    }

    public synchronized void recordNanos(long nanos) {
        histogram.recordValue(Math.max(1, nanos));
    }

    public synchronized double p50Millis() { return nanosToMillis(histogram.getValueAtPercentile(50)); }
    public synchronized double p95Millis() { return nanosToMillis(histogram.getValueAtPercentile(95)); }
    public synchronized double p99Millis() { return nanosToMillis(histogram.getValueAtPercentile(99)); }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
