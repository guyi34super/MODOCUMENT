package com.modocument.infrastructure.siem;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code modocument.siem.*} — the ws18 SIEM export (SEC-031, gate E6). Event
 * schema, transports and the field-stability promise:
 * docs/security/siem-event-schema.md.
 */
@ConfigurationProperties(prefix = "modocument.siem")
public class SiemProperties {

    private Feed feed = new Feed();
    private Webhook webhook = new Webhook();

    public Feed getFeed() {
        return feed;
    }

    public void setFeed(Feed feed) {
        this.feed = feed;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public void setWebhook(Webhook webhook) {
        this.webhook = webhook;
    }

    /** The bank-scoped pull feed, GET /api/siem/events. */
    public static class Feed {

        /**
         * Events younger than this are held back from the feed: a UUIDv7 id
         * is minted when the event is RECORDED, but the row becomes visible
         * when its transaction COMMITS — an event inside a slower
         * transaction could otherwise commit behind a cursor that already
         * passed its id. Keyset paging is gap-free for transactions shorter
         * than this lag.
         */
        private Duration settle = Duration.ofSeconds(5);
        private int defaultLimit = 500;
        private int maxLimit = 1000;

        public Duration getSettle() {
            return settle;
        }

        public void setSettle(Duration settle) {
            this.settle = settle;
        }

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getMaxLimit() {
            return maxLimit;
        }

        public void setMaxLimit(int maxLimit) {
            this.maxLimit = maxLimit;
        }
    }

    /**
     * The optional push forwarder. SINGLE-BANK DEPLOYMENT ASSUMPTION: one
     * MoDocument deployment pushes ONE bank's events ({@code bank-code}) to
     * that bank's collector; a multi-bank deployment uses the pull feed,
     * where every bank's SIEM authenticates as its own admin and can only
     * ever read its own events (gate E6).
     */
    public static class Webhook {

        private String url = "";
        /** Bank.Code whose events are pushed; required when url is set. */
        private String bankCode = "";
        /** Optional HMAC-SHA256 key: X-MoDocument-Signature over each body. */
        private String secret = "";
        private int batchSize = 100;
        /** Bounded in-memory retry queue; overflow drops the oldest (pull feed backfills). */
        private int queueCapacity = 10_000;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(10);

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getBankCode() {
            return bankCode;
        }

        public void setBankCode(String bankCode) {
            this.bankCode = bankCode;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
