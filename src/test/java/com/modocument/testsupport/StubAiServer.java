package com.modocument.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback stand-in for an OpenAI-compatible chat endpoint (ws23): point
 * {@code spring.ai.openai.base-url} at {@link #baseUrl()} and choose how every
 * {@code POST /v1/chat/completions} is answered — the Java twin of the e2e
 * fake LLM's {@code error500} and {@code timeout} modes. Counts the calls it
 * receives and never logs or keeps request bodies.
 */
public final class StubAiServer implements AutoCloseable {

    public enum Mode {
        /** 500 with an OpenAI-style error body. */
        ERROR_500,
        /** Accepts the request and never answers (until {@link #close()} or a mode change). */
        HANG,
        /** 400 with an OpenAI-style error body. */
        ERROR_400,
        /** 200 with a chat completion whose content is {@link #answer(String)} (QA, v1.2.0). */
        OK
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "stub-ai");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Mode mode = Mode.ERROR_500;
    private volatile String answer = "{}";
    private volatile CountDownLatch release = new CountDownLatch(1);

    public StubAiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the stub AI server", e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** The host root, without {@code /v1} (Spring AI appends the path). */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void mode(Mode value) {
        release.countDown();
        release = new CountDownLatch(1);
        mode = value;
    }

    /** The assistant content OK mode answers with (a JSON document, as the prompts ask). */
    public void answer(String content) {
        answer = content;
    }

    public int calls() {
        return calls.get();
    }

    public void resetCalls() {
        calls.set(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            switch (mode) {
                case HANG -> {
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                case ERROR_400 -> respond(exchange, 400,
                        "{\"error\":{\"message\":\"Invalid request\",\"type\":\"invalid_request_error\"}}");
                case OK -> respond(exchange, 200, completion(answer));
                default -> respond(exchange, 500,
                        "{\"error\":{\"message\":\"The server had an error\",\"type\":\"server_error\"}}");
            }
        }
    }

    private static String completion(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":0,"
                + "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":"
                + "\"assistant\",\"content\":\"" + escaped + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
