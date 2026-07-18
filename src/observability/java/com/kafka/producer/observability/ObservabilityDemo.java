package com.kafka.producer.observability;

import com.kafka.producer.pool.ExecutionOptions;
import com.kafka.producer.pool.PoolConfig;
import com.kafka.producer.pool.TransactionalProducerPool;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runnable local workload used by the provisioned Prometheus and Grafana stack. */
public final class ObservabilityDemo {
    private ObservabilityDemo() {
    }

    public static void main(String[] args) throws Exception {
        String bootstrapServers = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topic = env("KAFKA_TOPIC", "observability-demo");
        int port = Integer.parseInt(env("METRICS_PORT", "9404"));
        int delayMs = Integer.parseInt(env("DEMO_DELAY_MS", "100"));

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config()
                .commonTags(
                        "application", env("APPLICATION", "producer-pool-demo"),
                        "environment", env("ENVIRONMENT", "local"),
                        "instance", env("INSTANCE", "demo-1"),
                        "cluster", env("KAFKA_CLUSTER", "local-kafka"),
                        "pool", env("POOL_NAME", "default"))
                .meterFilter(histograms())
                .meterFilter(MeterFilter.deny(id -> id.getTags().stream().anyMatch(tag ->
                        tag.getKey().equals("transactional_id")
                                || tag.getKey().equals("correlation_id")
                                || tag.getKey().equals("publish_id")
                                || tag.getKey().equals("record_key")
                                || tag.getKey().equals("thread"))));

        new ClassLoaderMetrics().bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);

        Properties kafka = new Properties();
        kafka.setProperty("bootstrap.servers", bootstrapServers);
        PoolConfig config = PoolConfig.builder()
                .poolSize(4)
                .minHealthyProducers(2)
                .leaseTimeoutMs(2_000)
                .leaseHardTimeoutMs(30_000)
                .serviceIdentity("observability-demo")
                .instanceIdentifier(env("INSTANCE", "demo-1"))
                .kafkaProperties(kafka)
                .build();

        waitForKafka(bootstrapServers);
        TransactionalProducerPool pool = new TransactionalProducerPool(
                config, new com.kafka.producer.pool.DefaultKafkaProducerFactory(), registry);
        pool.initialize();

        MetricsHttpServer http = new MetricsHttpServer(port, registry);
        http.start();

        CountDownLatch stopped = new CountDownLatch(1);
        Thread workload = new Thread(() -> runWorkload(pool, topic, delayMs, stopped), "demo-workload");
        workload.setDaemon(true);
        workload.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopped.countDown();
            pool.shutdown();
            http.close();
            registry.close();
        }, "demo-shutdown"));

        System.out.printf("Metrics available at http://localhost:%d/metrics%n", port);
        Thread.currentThread().join();
    }

    private static void waitForKafka(String bootstrapServers) throws InterruptedException {
        String firstBroker = bootstrapServers.split(",")[0].trim();
        int separator = firstBroker.lastIndexOf(':');
        String host = firstBroker.substring(0, separator);
        int port = Integer.parseInt(firstBroker.substring(separator + 1));
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= 30; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1_000);
                return;
            } catch (IOException e) {
                lastFailure = e;
                TimeUnit.SECONDS.sleep(2);
            }
        }
        throw new IllegalStateException("Kafka was not ready after 60 seconds", lastFailure);
    }

    private static void runWorkload(TransactionalProducerPool pool, String topic, int delayMs,
                                    CountDownLatch stopped) {
        long sequence = 0;
        while (stopped.getCount() > 0) {
            try {
                byte[] key = ("key-" + (sequence % 16)).getBytes(StandardCharsets.UTF_8);
                byte[] value = ("event-" + sequence).getBytes(StandardCharsets.UTF_8);
                pool.executeInTransaction(lease -> {
                    lease.send(new ProducerRecord<>(topic, key, value));
                    return null;
                }, ExecutionOptions.defaults());
                sequence++;
                stopped.await(delayMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                try {
                    stopped.await(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static MeterFilter histograms() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(
                    io.micrometer.core.instrument.Meter.Id id,
                    DistributionStatisticConfig config) {
                if (id.getName().equals("lease_wait_ms")
                        || id.getName().equals("transaction_duration_ms")) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .serviceLevelObjectives(
                                    Duration.ofMillis(1).toNanos(),
                                    Duration.ofMillis(5).toNanos(),
                                    Duration.ofMillis(10).toNanos(),
                                    Duration.ofMillis(25).toNanos(),
                                    Duration.ofMillis(50).toNanos(),
                                    Duration.ofMillis(100).toNanos(),
                                    Duration.ofMillis(250).toNanos(),
                                    Duration.ofMillis(500).toNanos(),
                                    Duration.ofSeconds(1).toNanos(),
                                    Duration.ofSeconds(2).toNanos(),
                                    Duration.ofSeconds(5).toNanos(),
                                    Duration.ofSeconds(10).toNanos())
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
}
