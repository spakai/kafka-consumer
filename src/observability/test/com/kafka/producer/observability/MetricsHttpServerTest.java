package com.kafka.producer.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsHttpServerTest {

    @Test
    void servesPrometheusTextWithCommonTagsAndHistogramBuckets() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config()
                .commonTags("application", "test-app", "environment", "test",
                        "instance", "test-1", "cluster", "test-kafka", "pool", "default")
                .meterFilter(new io.micrometer.core.instrument.config.MeterFilter() {
                    @Override
                    public DistributionStatisticConfig configure(
                            io.micrometer.core.instrument.Meter.Id id,
                            DistributionStatisticConfig config) {
                        if (id.getName().equals("transaction_duration_ms")) {
                            return DistributionStatisticConfig.builder()
                                    .percentilesHistogram(true)
                                    .build()
                                    .merge(config);
                        }
                        return config;
                    }
                });
        Timer timer = Timer.builder("transaction_duration_ms").register(registry);
        timer.record(Duration.ofMillis(25));
        registry.counter("transaction_outcome_total", "outcome", "COMMITTED").increment();

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        try (MetricsHttpServer server = new MetricsHttpServer(port, registry)) {
            server.start();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("content-type").orElse("")
                    .startsWith("text/plain"));
            assertTrue(response.body().contains("transaction_duration_ms_seconds_bucket"));
            assertTrue(response.body().contains("transaction_outcome_total"));
            assertTrue(response.body().contains("application=\"test-app\""));
            assertFalse(response.body().contains("publish_id="));
        } finally {
            registry.close();
        }
    }
}
