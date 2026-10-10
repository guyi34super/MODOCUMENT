package com.modocument.infrastructure.alerting;

import com.modocument.domain.model.AlertSeverity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code modocument.alerts.*} — the ws18 alert framework's configuration
 * (gate D1, SEC-032 "configurable thresholds"). The rule catalogue is DATA:
 * {@code modocument.alerts.rules} binds a list of {@link Rule}s, shipped with
 * sane defaults in application.yml (thresholds overridable per environment
 * variable) and validated at startup by {@link AlertRuleCatalogue}. The
 * catalogue, channel set and rationale are documented in
 * docs/security/alerting-and-export-caps.md.
 */
@ConfigurationProperties(prefix = "modocument.alerts")
public class AlertProperties {

    /** Master switch: off means no rule is evaluated (the audit trail is unaffected). */
    private boolean enabled = true;
    private Sweep sweep = new Sweep();
    private Delivery delivery = new Delivery();
    private Webhook webhook = new Webhook();
    private Email email = new Email();
    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Sweep getSweep() {
        return sweep;
    }

    public void setSweep(Sweep sweep) {
        this.sweep = sweep;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public void setDelivery(Delivery delivery) {
        this.delivery = delivery;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public void setWebhook(Webhook webhook) {
        this.webhook = webhook;
    }

    public Email getEmail() {
        return email;
    }

    public void setEmail(Email email) {
        this.email = email;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * The minute sweep (windowed rules, delivery retries, SIEM push retries).
     * {@code enabled} gates only the SCHEDULE; {@code AlertSweepJob#runOnce}
     * always works (tests drive it directly).
     */
    public static class Sweep {

        private boolean enabled = true;
        /** Fixed delay between sweeps; must stay well under the 5-minute target. */
        private long intervalMs = 60_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    /** Delivery behaviour shared by every channel. */
    public static class Delivery {

        /** In-dispatch attempts per channel before the sweep takes over. */
        private int attempts = 2;
        /** How long the sweep keeps retrying a failing channel before FAILED. */
        private Duration retryWindow = Duration.ofMinutes(30);
        /** Alert executor size (bounded; a saturated queue logs the alert instead). */
        private int threads = 2;
        private int queueCapacity = 1000;

        public int getAttempts() {
            return attempts;
        }

        public void setAttempts(int attempts) {
            this.attempts = attempts;
        }

        public Duration getRetryWindow() {
            return retryWindow;
        }

        public void setRetryWindow(Duration retryWindow) {
            this.retryWindow = retryWindow;
        }

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    /**
     * Generic JSON webhook — the PHONE PATH (Slack/Teams incoming webhook, a
     * WhatsApp/SMS bridge, PagerDuty/Opsgenie event endpoint). Off when the
     * url is blank. Operator configuration, never user input: see
     * {@link WebhookAlertChannel} for why it bypasses OutboundHostPolicy.
     */
    public static class Webhook {

        private String url = "";
        /** Optional HMAC-SHA256 key: signs each body (X-MoDocument-Signature). */
        private String secret = "";
        private AlertSeverity minSeverity = AlertSeverity.INFO;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(5);

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public AlertSeverity getMinSeverity() {
            return minSeverity;
        }

        public void setMinSeverity(AlertSeverity minSeverity) {
            this.minSeverity = minSeverity;
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

    /**
     * E-mail (also an SMS path through an e-mail-to-SMS gateway). Active only
     * when {@code to} is set AND Spring's mail sender is configured
     * ({@code spring.mail.host}); a no-op otherwise.
     */
    public static class Email {

        private List<String> to = new ArrayList<>();
        private String from = "modocument-alerts@localhost";
        private AlertSeverity minSeverity = AlertSeverity.INFO;

        public List<String> getTo() {
            return to;
        }

        public void setTo(List<String> to) {
            this.to = to;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public AlertSeverity getMinSeverity() {
            return minSeverity;
        }

        public void setMinSeverity(AlertSeverity minSeverity) {
            this.minSeverity = minSeverity;
        }
    }

    /** One catalogue entry, as bound from configuration (validated into an {@link AlertRule}). */
    public static class Rule {

        private String name;
        private String description = "";
        private AlertRule.Trigger trigger;
        /** Audit actions the rule matches; {@code "*"} matches every action. */
        private List<String> actions = new ArrayList<>();
        /** Optional outcome filter (SUCCESS/DENIED/FAILED); empty = any. */
        private List<String> outcomes = new ArrayList<>();
        /** Optional ActorRole filter (e.g. ADMIN); empty = any. */
        private List<String> actorRoles = new ArrayList<>();
        private AlertSeverity severity = AlertSeverity.MEDIUM;
        /** WINDOWED: events per group within the window that raise the alert. */
        private int threshold = 1;
        /** WINDOWED: aggregation window; both triggers: the dedupe window. */
        private Duration window = Duration.ofMinutes(5);
        private AlertRule.GroupBy groupBy = AlertRule.GroupBy.ACTOR;
        private boolean enabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public AlertRule.Trigger getTrigger() {
            return trigger;
        }

        public void setTrigger(AlertRule.Trigger trigger) {
            this.trigger = trigger;
        }

        public List<String> getActions() {
            return actions;
        }

        public void setActions(List<String> actions) {
            this.actions = actions;
        }

        public List<String> getOutcomes() {
            return outcomes;
        }

        public void setOutcomes(List<String> outcomes) {
            this.outcomes = outcomes;
        }

        public List<String> getActorRoles() {
            return actorRoles;
        }

        public void setActorRoles(List<String> actorRoles) {
            this.actorRoles = actorRoles;
        }

        public AlertSeverity getSeverity() {
            return severity;
        }

        public void setSeverity(AlertSeverity severity) {
            this.severity = severity;
        }

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public AlertRule.GroupBy getGroupBy() {
            return groupBy;
        }

        public void setGroupBy(AlertRule.GroupBy groupBy) {
            this.groupBy = groupBy;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
