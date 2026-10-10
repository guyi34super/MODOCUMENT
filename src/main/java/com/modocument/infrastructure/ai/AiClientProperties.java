package com.modocument.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI client resilience (ws23, QA finding OBS-03), bound from
 * {@code modocument.ai.*}. Infrastructure wiring, so not an editable setting.
 * The retry policy itself is Spring AI's {@code spring.ai.retry.*}; both live
 * in the ws23 block at the end of application.yml.
 */
@ConfigurationProperties(prefix = "modocument.ai")
public class AiClientProperties {

    private final Http http = new Http();
    private final Extraction extraction = new Extraction();

    public Http getHttp() {
        return http;
    }

    public Extraction getExtraction() {
        return extraction;
    }

    /** HTTP client timeouts for the model endpoint (Spring Boot's default has none). */
    public static class Http {

        /** Time to establish the TCP connection to the AI endpoint. */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /**
         * Time to wait for the model's reply to one request. A local model
         * writing a long table can take minutes; a hung endpoint must not
         * hold a job forever.
         */
        private Duration readTimeout = Duration.ofSeconds(120);

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

    /** Limits of the AI path in the ingestion parse stage. */
    public static class Extraction {

        /**
         * Total time batched LLM extraction may spend on one document in
         * AI_WITH_CODE_FALLBACK mode; batches not started by then are parsed
         * by code (AI_PARTIAL, reason TIMEOUT).
         */
        private Duration timeBudget = Duration.ofMinutes(10);

        public Duration getTimeBudget() {
            return timeBudget;
        }

        public void setTimeBudget(Duration timeBudget) {
            this.timeBudget = timeBudget;
        }
    }
}
