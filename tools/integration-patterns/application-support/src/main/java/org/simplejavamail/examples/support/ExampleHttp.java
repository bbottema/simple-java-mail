package org.simplejavamail.examples.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Let a developer submit a synthetic job and inspect what happened without installing another client.
 * This small local HTTP adapter keeps request parsing out of the mail examples; it is not production authentication or an SJM web framework.
 */
public final class ExampleHttp implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_REQUEST_BYTES = 12 * 1024 * 1024;
    private final HttpServer server;
    private final ExecutorService workers = applicationWorkers(8);

    public ExampleHttp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 32);
        server.setExecutor(workers);
        route("/health", request -> Map.of("status", "UP"));
    }

    public void route(String prefix, Handler handler) {
        server.createContext(prefix, exchange -> {
            try (exchange) {
                int status = 200;
                Object result;
                try {
                    byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                    if (body.length > MAX_REQUEST_BYTES) {
                        throw new Rejection(413, "This demo request is too large");
                    }
                    result = handler.respond(new Request(exchange.getRequestMethod(), exchange.getRequestURI(), body, exchange.getRequestHeaders()));
                } catch (Rejection rejected) {
                    status = rejected.status;
                    result = Map.of("error", rejected.getMessage());
                } catch (Exception failed) {
                    status = 500;
                    // Do not expose callback exceptions, credentials or message content through the diagnostic endpoint.
                    System.err.println("Demo request failed: " + failed.getClass().getSimpleName());
                    result = Map.of("error", "The operation failed; inspect this demo's persisted evidence");
                }
                byte[] output = JSON.writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, output.length);
                exchange.getResponseBody().write(output);
            }
        });
    }

    public void start() {
        server.start();
    }

    public static ExecutorService applicationWorkers(int count) {
        ClassLoader applicationLoader = Thread.currentThread().getContextClassLoader();
        return Executors.newFixedThreadPool(count, task -> {
            Thread thread = new Thread(task, "pattern-http");
            // JDK HttpServer creates workers from its system-loader dispatcher. Preserve the executable JAR's application loader for SPI discovery.
            thread.setContextClassLoader(applicationLoader);
            return thread;
        });
    }

    @Override
    public void close() {
        try {
            server.stop(1);
        } finally {
            workers.shutdown();
        }
    }

    public static byte[] getBytes(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        // This evidence fetch owns its short-lived client, including the client's background connection resources.
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("The local fixture did not return the requested evidence");
            }
            return response.body();
        }
    }

    public static String environment(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }

    @FunctionalInterface
    public interface Handler {
        Object respond(Request request) throws Exception;
    }

    public record Request(String method, URI uri, byte[] body, Map<String, java.util.List<String>> headers) {
        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .findFirst().map(entry -> entry.getValue().getFirst()).orElse("");
        }

        public Map<String, String> parameters() {
            Map<String, String> result = new LinkedHashMap<>();
            if (uri.getRawQuery() != null) {
                for (String part : uri.getRawQuery().split("&")) {
                    String[] pair = part.split("=", 2);
                    result.put(decode(pair[0]), pair.length == 2 ? decode(pair[1]) : "");
                }
            }
            return result;
        }

        public Map<String, Object> json() throws IOException {
            return JSON.readValue(body, new TypeReference<>() { });
        }

        private static String decode(String text) {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        }
    }

    public static final class Rejection extends RuntimeException {
        private final int status;
        public Rejection(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
