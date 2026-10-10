package com.modocument.testsupport;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP receiver on loopback for the ws18 webhook channels (alert
 * webhook, SIEM push): records every request (path, headers, body) and
 * answers 200 — or a configurable error status, to exercise the retry
 * paths. One JVM-wide instance on an ephemeral port (parallel builds on the
 * same machine never collide), started lazily.
 */
public final class WebhookStub {

    /** One received request. */
    public record Received(String path, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    private static WebhookStub instance;

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    private final Map<String, AtomicInteger> failNext = new ConcurrentHashMap<>();

    private WebhookStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String path = exchange.getRequestURI().getPath();
            received.add(new Received(path, Map.copyOf(exchange.getRequestHeaders()), body));
            AtomicInteger failures = failNext.get(path);
            int status = failures != null && failures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0
                    ? 503 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
    }

    public static synchronized WebhookStub get() {
        if (instance == null) {
            instance = new WebhookStub();
        }
        return instance;
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Snapshot of everything received on {@code path}, in arrival order. */
    public List<Received> receivedOn(String path) {
        return received.stream().filter(r -> r.path().equals(path)).toList();
    }

    /** The next {@code count} requests on {@code path} are answered 503. */
    public void failNext(String path, int count) {
        failNext.computeIfAbsent(path, p -> new AtomicInteger()).set(count);
    }
}
