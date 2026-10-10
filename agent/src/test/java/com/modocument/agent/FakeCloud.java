package com.modocument.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.HexFormat;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A JDK HttpServer fake of MoDocument's side of the agent protocol
 * (contracts §7.4) plus the client-credentials token endpoint: one-time
 * enrolment, token, long-poll tasks (from a queue), records, payload,
 * result, heartbeat. Every authenticated call must carry the issued token.
 */
public final class FakeCloud implements AutoCloseable {

    public static final String TOKEN = "mda1.5." + "A".repeat(43);
    public static final String CLIENT_ID = "agt_5";
    public static final String CLIENT_SECRET = "cs-very-secret-value";
    public static final String ACCESS_TOKEN = "at-fake-access-token";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RUN = Pattern.compile("/api/agent/v1/runs/(\\d+)/(records|payload|result)");

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Map<String, Object>> tasks = new ConcurrentLinkedQueue<>();
    public final List<Map<String, Object>> enrolments = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> results = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> heartbeats = new CopyOnWriteArrayList<>();
    public final List<String> paths = new CopyOnWriteArrayList<>();
    /** Form bodies of every token request, and whether it carried an Authorization header. */
    public final List<String> tokenForms = new CopyOnWriteArrayList<>();
    public final List<Boolean> tokenBasicAuth = new CopyOnWriteArrayList<>();
    private volatile boolean enrolled;
    private volatile Map<String, Object> payload = Map.of();
    /** MTLS enrolment: SHA-256 of the agent certificate; every later call must present it. */
    private volatile String enrolledCertificateSha256;

    public FakeCloud() throws IOException {
        this(null);
    }

    /**
     * @param tls null for plain http; otherwise an HTTPS server that ASKS for
     *            (but does not require) a client certificate — nginx's
     *            {@code ssl_verify_client optional}
     */
    public FakeCloud(SSLContext tls) throws IOException {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 0);
        if (tls == null) {
            server = HttpServer.create(address, 0);
        } else {
            HttpsServer https = HttpsServer.create(address, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(tls) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters parameters = tls.getDefaultSSLParameters();
                    parameters.setWantClientAuth(true);
                    params.setSSLParameters(parameters);
                }
            });
            server = https;
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        String scheme = server instanceof HttpsServer ? "https" : "http";
        return scheme + "://127.0.0.1:" + server.getAddress().getPort();
    }

    /** SHA-256 (hex) of the client certificate of this exchange, or null. */
    private static String presentedCertificate(HttpExchange exchange) {
        if (!(exchange instanceof HttpsExchange https)) {
            return null;
        }
        try {
            Certificate[] chain = https.getSSLSession().getPeerCertificates();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].getEncoded()));
        } catch (SSLPeerUnverifiedException none) {
            return null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256OfPem(String pem) {
        try {
            Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void queue(Map<String, Object> task) {
        tasks.add(task);
    }

    public void payload(Map<String, Object> batch) {
        this.payload = batch;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        paths.add(exchange.getRequestMethod() + " " + path);
        try {
            if (path.equals("/api/agent/v1/enroll")) {
                Map<String, Object> body = body(exchange);
                enrolments.add(body);
                if (!TOKEN.equals(body.get("token")) || enrolled) {
                    send(exchange, 401, Map.of("error", "invalid", "code", "AGENT_ENROLMENT_INVALID"));
                    return;
                }
                enrolled = true;
                Map<String, Object> answer = new java.util.LinkedHashMap<>();
                answer.put("agentId", 5);
                answer.put("clientId", CLIENT_ID);
                if ("MTLS".equals(body.get("authMethod"))) {
                    // Like the server: the certificate is registered, no secret is issued.
                    enrolledCertificateSha256 = sha256OfPem(String.valueOf(body.get("certificatePem")));
                } else {
                    answer.put("secret", CLIENT_SECRET);
                }
                answer.put("tokenUrl", url() + "/api/integration/oauth/token");
                send(exchange, 200, answer);
                return;
            }
            String presented = presentedCertificate(exchange);
            if (path.equals("/api/integration/oauth/token")) {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                tokenForms.add(form);
                tokenBasicAuth.add(authorization != null);
                boolean ok;
                if (enrolledCertificateSha256 != null) {
                    // tls_client_auth: the client certificate is the credential.
                    ok = authorization == null && enrolledCertificateSha256.equals(presented)
                            && form.contains("client_id=" + CLIENT_ID);
                } else {
                    String expected = "Basic " + Base64.getEncoder().encodeToString(
                            (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
                    ok = expected.equals(authorization);
                }
                if (!ok) {
                    send(exchange, 401, Map.of("error", "invalid_client", "code", "INVALID_CLIENT"));
                    return;
                }
                send(exchange, 200, Map.of("access_token", ACCESS_TOKEN, "token_type", "Bearer",
                        "expires_in", 600, "scope", "AGENT_CONNECT"));
                return;
            }
            boolean bound = enrolledCertificateSha256 == null || enrolledCertificateSha256.equals(presented);
            if (!bound || !("Bearer " + ACCESS_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            if (path.equals("/api/agent/v1/tasks")) {
                List<Map<String, Object>> batch = new ArrayList<>();
                Map<String, Object> next;
                while ((next = tasks.poll()) != null) {
                    batch.add(next);
                }
                send(exchange, 200, Map.of("tasks", batch));
                return;
            }
            if (path.equals("/api/agent/v1/heartbeat")) {
                heartbeats.add(body(exchange));
                send(exchange, 200, Map.of("latestVersion", "9.9.9", "pollSeconds", 30));
                return;
            }
            Matcher run = RUN.matcher(path);
            if (run.matches()) {
                switch (run.group(2)) {
                    case "records" -> {
                        Map<String, Object> body = body(exchange);
                        records.add(Map.of("runId", Long.parseLong(run.group(1)), "body", body));
                        send(exchange, 204, null);
                    }
                    case "result" -> {
                        Map<String, Object> body = new java.util.LinkedHashMap<>(body(exchange));
                        body.put("runId", Long.parseLong(run.group(1)));
                        results.add(body);
                        send(exchange, 204, null);
                    }
                    default -> send(exchange, 200, payload);
                }
                return;
            }
            send(exchange, 404, Map.of("error", "not found"));
        } catch (RuntimeException e) {
            send(exchange, 500, Map.of("error", e.getClass().getSimpleName()));
        }
    }

    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return MAPPER.readValue(bytes, new TypeReference<>() { });
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
