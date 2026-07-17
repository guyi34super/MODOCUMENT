package com.modocument.infrastructure.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning knobs for the data extraction feature (store selection, worker pool,
 * retry policy, and the compliant web fetcher). Bound from extraction.* keys.
 */
@ConfigurationProperties(prefix = "extraction")
public class ExtractionProperties {

    /** "mongo" (production) or "memory" (local/test). */
    private String store = "memory";
    private final Kafka kafka = new Kafka();
    private final Worker worker = new Worker();
    private final Web web = new Web();
    /** Rows per bounded batch when streaming records (never the whole set). */
    private int batchSize = 500;

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Worker getWorker() {
        return worker;
    }

    public Web getWeb() {
        return web;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public static class Kafka {
        private boolean enabled = false;
        private String taskTopic = "extraction.task-events";
        private String deadLetterTopic = "extraction.tasks.dlq";
        private String groupId = "modocument-extraction-workers";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTaskTopic() {
            return taskTopic;
        }

        public void setTaskTopic(String taskTopic) {
            this.taskTopic = taskTopic;
        }

        public String getDeadLetterTopic() {
            return deadLetterTopic;
        }

        public void setDeadLetterTopic(String deadLetterTopic) {
            this.deadLetterTopic = deadLetterTopic;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }
    }

    public static class Worker {
        private boolean enabled = true;
        private int threads = 4;
        private int leaseSeconds = 60;
        private long pollMillis = 500;
        private int shutdownGraceSeconds = 20;
        private int maxAttempts = 4;
        private long backoffMillis = 5000;
        private long sweepMillis = 15000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public long getPollMillis() {
            return pollMillis;
        }

        public void setPollMillis(long pollMillis) {
            this.pollMillis = pollMillis;
        }

        public int getShutdownGraceSeconds() {
            return shutdownGraceSeconds;
        }

        public void setShutdownGraceSeconds(int shutdownGraceSeconds) {
            this.shutdownGraceSeconds = shutdownGraceSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffMillis() {
            return backoffMillis;
        }

        public void setBackoffMillis(long backoffMillis) {
            this.backoffMillis = backoffMillis;
        }

        public long getSweepMillis() {
            return sweepMillis;
        }

        public void setSweepMillis(long sweepMillis) {
            this.sweepMillis = sweepMillis;
        }
    }

    public static class Web {
        private String userAgent = "MoDocumentBot/1.0 (+https://example.com/bot)";
        private long defaultDelayMillis = 1000;
        private long robotsTtlSeconds = 3600;
        private int maxBodyBytes = 5 * 1024 * 1024;
        private int requestTimeoutSeconds = 20;
        private boolean renderJsEnabled = false;
        private int maxPagesDefault = 100;
        private int maxDepthDefault = 2;

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public long getDefaultDelayMillis() {
            return defaultDelayMillis;
        }

        public void setDefaultDelayMillis(long defaultDelayMillis) {
            this.defaultDelayMillis = defaultDelayMillis;
        }

        public long getRobotsTtlSeconds() {
            return robotsTtlSeconds;
        }

        public void setRobotsTtlSeconds(long robotsTtlSeconds) {
            this.robotsTtlSeconds = robotsTtlSeconds;
        }

        public int getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }

        public boolean isRenderJsEnabled() {
            return renderJsEnabled;
        }

        public void setRenderJsEnabled(boolean renderJsEnabled) {
            this.renderJsEnabled = renderJsEnabled;
        }

        public int getMaxPagesDefault() {
            return maxPagesDefault;
        }

        public void setMaxPagesDefault(int maxPagesDefault) {
            this.maxPagesDefault = maxPagesDefault;
        }

        public int getMaxDepthDefault() {
            return maxDepthDefault;
        }

        public void setMaxDepthDefault(int maxDepthDefault) {
            this.maxDepthDefault = maxDepthDefault;
        }
    }
}
