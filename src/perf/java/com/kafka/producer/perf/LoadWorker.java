package com.kafka.producer.perf;

import com.kafka.producer.pool.LeaseTimeoutException;
import com.kafka.producer.pool.PoolSaturationException;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LoadWorker implements Callable<LoadWorker.Stats> {

    @FunctionalInterface
    public interface Operation {
        int execute() throws Exception;
    }

    public static final class Stats {
        public long successfulTx;
        public long successfulMessages;
        public long leaseTimeouts;
        public long saturations;
        public long failures;
    }

    private final AtomicBoolean stop;
    private final Operation operation;
    private final LatencyRecorder txLatency;

    public LoadWorker(AtomicBoolean stop, Operation operation, LatencyRecorder txLatency) {
        this.stop = stop;
        this.operation = operation;
        this.txLatency = txLatency;
    }

    @Override
    public Stats call() {
        Stats stats = new Stats();
        while (!stop.get()) {
            long start = System.nanoTime();
            try {
                int sent = operation.execute();
                txLatency.recordNanos(System.nanoTime() - start);
                stats.successfulTx++;
                stats.successfulMessages += sent;
            } catch (LeaseTimeoutException e) {
                stats.leaseTimeouts++;
            } catch (PoolSaturationException e) {
                stats.saturations++;
            } catch (Exception e) {
                stats.failures++;
            }
        }
        return stats;
    }

    public static String timestamp() {
        return Instant.now().toString().replace(':', '-');
    }
}
