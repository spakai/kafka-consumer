package com.kafka.producer.chaos;

import java.util.concurrent.atomic.AtomicLongArray;

final class LatencyWindow {
    private static final long[] UPPER_BOUNDS_NANOS = {
            1_000_000L,
            2_000_000L,
            5_000_000L,
            10_000_000L,
            20_000_000L,
            50_000_000L,
            100_000_000L,
            200_000_000L,
            500_000_000L,
            1_000_000_000L,
            2_000_000_000L,
            5_000_000_000L,
            10_000_000_000L,
            30_000_000_000L,
            60_000_000_000L,
            Long.MAX_VALUE
    };

    private final AtomicLongArray buckets = new AtomicLongArray(UPPER_BOUNDS_NANOS.length);

    void record(long nanos) {
        long value = Math.max(1, nanos);
        for (int i = 0; i < UPPER_BOUNDS_NANOS.length; i++) {
            if (value <= UPPER_BOUNDS_NANOS[i]) {
                buckets.incrementAndGet(i);
                return;
            }
        }
    }

    Snapshot snapshotAndReset() {
        long[] counts = new long[UPPER_BOUNDS_NANOS.length];
        long total = 0;
        for (int i = 0; i < counts.length; i++) {
            counts[i] = buckets.getAndSet(i, 0);
            total += counts[i];
        }
        return new Snapshot(counts, total);
    }

    record Snapshot(long[] counts, long total) {
        double percentileMillis(double percentile) {
            if (total == 0) {
                return 0;
            }
            long target = Math.max(1, (long) Math.ceil(total * percentile / 100.0));
            long cumulative = 0;
            for (int i = 0; i < counts.length; i++) {
                cumulative += counts[i];
                if (cumulative >= target) {
                    long bound = UPPER_BOUNDS_NANOS[i];
                    return bound == Long.MAX_VALUE
                            ? 60_000.0 : bound / 1_000_000.0;
                }
            }
            return 60_000.0;
        }
    }
}
