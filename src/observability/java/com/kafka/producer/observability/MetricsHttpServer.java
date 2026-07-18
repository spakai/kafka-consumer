package com.kafka.producer.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

final class MetricsHttpServer implements AutoCloseable {
    private final HttpServer server;

    MetricsHttpServer(int port, PrometheusMeterRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> respond(exchange, registry.scrape()));
        server.createContext("/health", exchange -> respond(exchange, "ok\n"));
        server.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "observability-http");
            thread.setDaemon(true);
            return thread;
        }));
    }

    void start() {
        server.start();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(1);
    }
}
