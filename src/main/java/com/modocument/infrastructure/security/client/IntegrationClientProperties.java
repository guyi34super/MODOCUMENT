package com.modocument.infrastructure.security.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Machine identity and integration API knobs (ws26) — the
 * {@code modocument.integration.clients.*} / {@code modocument.integration.api.*}
 * blocks. The security-relevant per-bank values (client token lifetime,
 * which auth methods a bank may use) are editable SETTINGS
 * ({@code integration.clientTokenTtl}, {@code integration.auth.allow*});
 * these are the deployment-level ones.
 */
@ConfigurationProperties(prefix = "modocument.integration")
public class IntegrationClientProperties {

    private final Clients clients = new Clients();
    private final Api api = new Api();

    public Clients getClients() {
        return clients;
    }

    public Api getApi() {
        return api;
    }

    public static class Clients {

        /**
         * TCP peers (the proxy hop in front of the app — nginx) whose
         * {@code X-Amzn-Mtls-Clientcert-*} headers are trusted. Empty (the
         * default) = mutual TLS headers are never trusted. The headers are
         * read only when the request's SOCKET peer (before the forwarded-header
         * rewrite) is inside one of these CIDRs.
         */
        private List<String> mtlsTrustedProxyCidrs = new ArrayList<>();

        /** How long a verified API key (keyed by its SHA-256) skips the Argon2 check. */
        private Duration apiKeyCacheTtl = Duration.ofSeconds(60);

        /** Argon2 verifications (token endpoint + API-key cache misses) per IP per window. */
        private int verifyPerIpMax = 60;

        private Duration verifyWindow = Duration.ofMinutes(15);

        /** How long the previous secret keeps working after a rotation. */
        private Duration secretOverlap = Duration.ofHours(24);

        /** How long the maker may read a new client's secret (once). */
        private Duration initialSecretTtl = Duration.ofHours(24);

        /** ExpiresAt of a new SYSTEM client when the request names none. */
        private Duration defaultExpiry = Duration.ofDays(365);

        /** The latest ExpiresAt a request may ask for (from now). */
        private Duration maxExpiry = Duration.ofDays(3 * 365);

        /** LastUsedAt is written at most this often per client. */
        private Duration lastUsedResolution = Duration.ofSeconds(60);

        public List<String> getMtlsTrustedProxyCidrs() {
            return mtlsTrustedProxyCidrs;
        }

        public void setMtlsTrustedProxyCidrs(List<String> mtlsTrustedProxyCidrs) {
            this.mtlsTrustedProxyCidrs = mtlsTrustedProxyCidrs == null
                    ? new ArrayList<>() : mtlsTrustedProxyCidrs;
        }

        public Duration getApiKeyCacheTtl() {
            return apiKeyCacheTtl;
        }

        public void setApiKeyCacheTtl(Duration apiKeyCacheTtl) {
            this.apiKeyCacheTtl = apiKeyCacheTtl;
        }

        public int getVerifyPerIpMax() {
            return verifyPerIpMax;
        }

        public void setVerifyPerIpMax(int verifyPerIpMax) {
            this.verifyPerIpMax = verifyPerIpMax;
        }

        public Duration getVerifyWindow() {
            return verifyWindow;
        }

        public void setVerifyWindow(Duration verifyWindow) {
            this.verifyWindow = verifyWindow;
        }

        public Duration getSecretOverlap() {
            return secretOverlap;
        }

        public void setSecretOverlap(Duration secretOverlap) {
            this.secretOverlap = secretOverlap;
        }

        public Duration getInitialSecretTtl() {
            return initialSecretTtl;
        }

        public void setInitialSecretTtl(Duration initialSecretTtl) {
            this.initialSecretTtl = initialSecretTtl;
        }

        public Duration getDefaultExpiry() {
            return defaultExpiry;
        }

        public void setDefaultExpiry(Duration defaultExpiry) {
            this.defaultExpiry = defaultExpiry;
        }

        public Duration getMaxExpiry() {
            return maxExpiry;
        }

        public void setMaxExpiry(Duration maxExpiry) {
            this.maxExpiry = maxExpiry;
        }

        public Duration getLastUsedResolution() {
            return lastUsedResolution;
        }

        public void setLastUsedResolution(Duration lastUsedResolution) {
            this.lastUsedResolution = lastUsedResolution;
        }
    }

    public static class Api {

        /** Requests per client per window on /api/integration/v1/** (bucket client:&lt;id&gt;). */
        private int perClientMax = 600;

        private Duration window = Duration.ofMinutes(1);

        /** Token requests per IP per window on /api/integration/oauth/token. */
        private int tokenPerIpMax = 120;

        /** Largest JSON body a POST may send. */
        private long maxBodyBytes = 10L * 1024 * 1024;

        /** Most records one POST /records may carry. */
        private int maxRecords = 10_000;

        /** Largest file a POST /files may upload. */
        private long maxFileBytes = 25L * 1024 * 1024;

        /** How long an Idempotency-Key is remembered. */
        private Duration idempotencyTtl = Duration.ofHours(24);

        /** Longest Idempotency-Key accepted. */
        private int idempotencyKeyMaxLength = 255;

        /** Largest stored replay body; bigger answers are not replayable (409 on reuse). */
        private int idempotencyMaxResponseChars = 16 * 1024;

        /** Default and maximum page size of GET /events. */
        private int eventsDefaultLimit = 100;

        private int eventsMaxLimit = 500;

        public int getPerClientMax() {
            return perClientMax;
        }

        public void setPerClientMax(int perClientMax) {
            this.perClientMax = perClientMax;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public int getTokenPerIpMax() {
            return tokenPerIpMax;
        }

        public void setTokenPerIpMax(int tokenPerIpMax) {
            this.tokenPerIpMax = tokenPerIpMax;
        }

        public long getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(long maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public int getMaxRecords() {
            return maxRecords;
        }

        public void setMaxRecords(int maxRecords) {
            this.maxRecords = maxRecords;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public Duration getIdempotencyTtl() {
            return idempotencyTtl;
        }

        public void setIdempotencyTtl(Duration idempotencyTtl) {
            this.idempotencyTtl = idempotencyTtl;
        }

        public int getIdempotencyKeyMaxLength() {
            return idempotencyKeyMaxLength;
        }

        public void setIdempotencyKeyMaxLength(int idempotencyKeyMaxLength) {
            this.idempotencyKeyMaxLength = idempotencyKeyMaxLength;
        }

        public int getIdempotencyMaxResponseChars() {
            return idempotencyMaxResponseChars;
        }

        public void setIdempotencyMaxResponseChars(int idempotencyMaxResponseChars) {
            this.idempotencyMaxResponseChars = idempotencyMaxResponseChars;
        }

        public int getEventsDefaultLimit() {
            return eventsDefaultLimit;
        }

        public void setEventsDefaultLimit(int eventsDefaultLimit) {
            this.eventsDefaultLimit = eventsDefaultLimit;
        }

        public int getEventsMaxLimit() {
            return eventsMaxLimit;
        }

        public void setEventsMaxLimit(int eventsMaxLimit) {
            this.eventsMaxLimit = eventsMaxLimit;
        }
    }
}
