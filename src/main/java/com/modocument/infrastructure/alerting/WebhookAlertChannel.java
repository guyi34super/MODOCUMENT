package com.modocument.infrastructure.alerting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.settings.SettingKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The PHONE PATH (gate D1): a generic JSON POST to
 * {@code modocument.alerts.webhook.url} — a Slack/Teams incoming webhook, a
 * WhatsApp/SMS bridge, a PagerDuty/Opsgenie events endpoint or an on-call
 * relay. Body:
 * <pre>
 * {"text": "[MoDocument HIGH] account-locked (bank 3, alert #12): ...",
 *  "alert": {"id": 12, "rule": "account-locked", "severity": "HIGH",
 *            "bankId": 3, "summary": "...", "auditEventId": "...",
 *            "eventCount": 1, "createdAt": "2026-10-09T10:15:30Z",
 *            "source": "modocument"}}
 * </pre>
 * {@code text} is what chat-style receivers display; {@code alert} is the
 * structured copy for anything that routes or deduplicates. With
 * {@code modocument.alerts.webhook.secret} set, every body is signed:
 * {@code X-MoDocument-Signature: sha256=<hex HMAC-SHA256(secret, body)>}.
 *
 * <p>WHY THIS DOES NOT GO THROUGH {@code OutboundHostPolicy}: that policy is
 * the SSRF guard for USER-SUPPLIED URLs (extraction sources, crawled links)
 * — it refuses loopback/private/link-local targets so a user cannot steer the
 * server into the internal network. This URL is OPERATOR configuration
 * (environment/YAML, the same trust level as the database URL); no request
 * parameter or stored user data can change it, and its legitimate targets
 * are frequently internal (an on-prem relay, a private-network SIEM/pager
 * gateway) which the SSRF policy would wrongly block. The plain
 * {@link SimpleClientHttpRequestFactory} honours the JVM proxy settings, so a
 * corporate egress proxy applies as for any other outbound call.
 */
@Component
public class WebhookAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertChannel.class);

    public static final String SIGNATURE_HEADER = "X-MoDocument-Signature";

    private final AlertProperties.Webhook config;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    /** Null = the yml minimum severity only (unit-test wiring). */
    private final SettingsReader settings;

    public WebhookAlertChannel(AlertProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    @Autowired
    public WebhookAlertChannel(AlertProperties properties, ObjectMapper objectMapper,
                               SettingsReader settings) {
        this.settings = settings;
        this.config = properties.getWebhook();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeout());
        factory.setReadTimeout(config.getReadTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
        if (enabled()) {
            URI uri = URI.create(config.getUrl().trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())) {
                log.warn("Alert webhook {}://{} is not HTTPS: alert summaries will travel in "
                        + "clear text.", uri.getScheme(), uri.getHost());
            }
            log.info("Alert webhook channel enabled ({}://{}, min severity {}, signed: {})",
                    uri.getScheme(), uri.getHost(), config.getMinSeverity(), signed());
        }
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public boolean enabled() {
        return config.getUrl() != null && !config.getUrl().isBlank();
    }


    /**
     * The platform setting {@code alerts.webhook.minSeverity} (INFO/LOW/MEDIUM; HIGH and CRITICAL
     * always pass), read per alert; with nothing stored the yml value.
     */
    @Override
    public AlertSeverity minSeverity() {
        if (settings == null) {
            return config.getMinSeverity();
        }
        String effective = settings.getEnumForPlatform(SettingKeys.ALERT_WEBHOOK_MIN_SEVERITY);
        try {
            return AlertSeverity.valueOf(effective.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return config.getMinSeverity();
        }
    }

    @Override
    public void deliver(AlertMessage alert) throws Exception {
        String body = objectMapper.writeValueAsString(payload(alert));
        RestClient.RequestBodySpec request = client.post()
                .uri(config.getUrl().trim())
                .contentType(MediaType.APPLICATION_JSON);
        if (signed()) {
            request = request.header(SIGNATURE_HEADER, "sha256=" + hmac(config.getSecret(), body));
        }
        // Any non-2xx raises: the alert is "not accepted" and will be retried.
        request.body(body).retrieve().toBodilessEntity();
    }

    private boolean signed() {
        return config.getSecret() != null && !config.getSecret().isBlank();
    }

    ObjectNode payload(AlertMessage alert) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("text", alert.text());
        ObjectNode node = root.putObject("alert");
        node.put("id", alert.alertId());
        node.put("rule", alert.rule());
        node.put("severity", alert.severity().name());
        node.put("bankId", alert.bankId());
        node.put("summary", alert.summary());
        node.put("auditEventId", alert.auditEventId() == null ? null : alert.auditEventId().toString());
        node.put("eventCount", alert.eventCount());
        node.put("createdAt", alert.createdAt() == null ? null : alert.createdAt().toString());
        node.put("source", "modocument");
        return root;
    }

    /** Hex HMAC-SHA256 of the body; shared with the SIEM push forwarder. */
    public static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable.", e);
        }
    }

    private static boolean isLoopback(String host) {
        return host != null && (host.equals("localhost") || host.startsWith("127.")
                || host.equals("[::1]") || host.equals("::1"));
    }
}
