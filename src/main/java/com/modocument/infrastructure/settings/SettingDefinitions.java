package com.modocument.infrastructure.settings;

import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingGroup;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.modocument.domain.model.settings.SettingDefinition.builder;
import static com.modocument.domain.model.settings.SettingKeys.*;
import static com.modocument.domain.model.settings.SettingType.BOOLEAN;
import static com.modocument.domain.model.settings.SettingType.DECIMAL;
import static com.modocument.domain.model.settings.SettingType.DURATION;
import static com.modocument.domain.model.settings.SettingType.ENUM;
import static com.modocument.domain.model.settings.SettingType.INT;

/**
 * THE settings catalogue (ws20, plan §1 "Initial catalogue"): every
 * UI-editable setting with its type, scope, tier, tab, guardrails and the
 * existing Spring property that supplies its shipped default. Rendered into
 * docs/settings/catalogue.md (SettingsCatalogueDocTest keeps the two in
 * sync) and pinned against application.yml + the secret/URL deny-list by
 * SettingsCatalogueSafetyTest.
 *
 * <p>Rules of the catalogue:
 * <ul>
 *   <li><b>Never here</b> — secrets, keys, URLs/hosts, credentials, CORS,
 *       the SSRF allow-list, storage root, cron expressions, multipart
 *       limits, AI endpoint/key/model: infrastructure wiring stays in
 *       environment configuration.</li>
 *   <li><b>Hard limits</b> are the go-live gate's floors and ceilings: no
 *       platform value, bank bound or bank override can leave them.</li>
 *   <li><b>No property</b> — settings that had no yml knob before ws20
 *       (circuit breaker, extraction mode/confidence, MFA for all roles, the
 *       AI master switch, the integration tab) carry only their code
 *       default.</li>
 * </ul>
 * Consumers read these through {@code SettingsReader} per call (Wave 2
 * consumer refactor); with no stored override every consumer keeps its
 * existing behaviour exactly ({@code SettingsConsumersParityTest}). A few
 * stay yml-only on purpose: the locked switches (rate limiting, retention,
 * PII tokenization) and the restart-only rate-limit window.
 *
 * <p><b>Alert tuning</b> ({@link #alertThreshold}/{@link #alertWindow})
 * exists for every WINDOWED rule of the shipped catalogue and names it in
 * its property ({@code modocument.alerts.rules[<rule>].threshold}); the alert
 * catalogue overlays stored values by that name and
 * {@code SettingsCatalogueSafetyTest} keeps the two lists in sync.
 */
public final class SettingDefinitions {

    private SettingDefinitions() {
    }

    /** Every setting, in display order (grouped by tab). */
    public static final List<SettingDefinition> ALL = List.of(
            // ================================================ Sign-in & passwords
            builder(PASSWORD_MIN_LENGTH, INT).bank().security().group(SettingGroup.SIGN_IN)
                    .label("Minimum password length")
                    .description("Shortest password a user may set (SEC-021). Long passphrases "
                            + "are always accepted; breached-list checks always apply.")
                    .unit("characters")
                    .property("modocument.security.password.min-length")
                    .defaultValue("12").range("12", "128").build(),
            builder(LOCKOUT_ALLOWED_ATTEMPTS, INT).bank().security().group(SettingGroup.SIGN_IN)
                    .label("Wrong passwords before lockout")
                    .description("Consecutive failed sign-ins allowed; the next one locks "
                            + "the account.")
                    .unit("attempts")
                    .property("modocument.security.lockout.allowed-attempts")
                    .defaultValue("5").range("3", "10").build(),
            builder(LOCKOUT_BASE_MINUTES, INT).bank().security().group(SettingGroup.SIGN_IN)
                    .label("First lockout duration")
                    .description("How long the first lockout lasts; it doubles with each "
                            + "further lockout up to the cap.")
                    .unit("minutes")
                    .property("modocument.security.lockout.base-minutes")
                    .defaultValue("1").range("1", "60").build(),
            builder(LOCKOUT_CAP_MINUTES, INT).bank().security().group(SettingGroup.SIGN_IN)
                    .label("Longest lockout duration")
                    .description("Upper bound of the doubling lockout duration.")
                    .unit("minutes")
                    .property("modocument.security.lockout.cap-minutes")
                    .defaultValue("30").range("30", "1440").build(),
            builder(SESSION_IDLE_TIMEOUT, DURATION).bank().security().group(SettingGroup.SIGN_IN)
                    .label("Idle session timeout")
                    .description("A session unused for this long must sign in again "
                            + "(the 7-day absolute limit is fixed).")
                    .property("modocument.security.session.idle-timeout", ChronoUnit.MILLIS)
                    .defaultValue("12h").range("15m", "12h").build(),
            builder(MFA_REQUIRE_FOR_ALL_ROLES, BOOLEAN).bank().security()
                    .group(SettingGroup.SIGN_IN)
                    .label("Require MFA for every role")
                    .description("Every user, not only administrators, must enrol a second "
                            + "factor before working.")
                    .defaultValue("false").build(),
            builder(MFA_REQUIRE_FOR_ADMINS, BOOLEAN).bank().security().group(SettingGroup.SIGN_IN)
                    .label("Require MFA for administrators")
                    .description("Administrators always need a second factor (gate B3).")
                    .property("modocument.security.mfa.require-for-admin")
                    .locked("true").build(),
            builder(JWT_TTL_MINUTES, INT).platform().security().group(SettingGroup.SIGN_IN)
                    .label("Access token lifetime")
                    .description("Lifetime of a signed-in user's access token; the refresh "
                            + "cookie renews it.")
                    .unit("minutes")
                    .property("modocument.security.jwt.ttl-minutes")
                    .defaultValue("15").range("5", "15").build(),

            // ======================================================== Rate limits
            builder(RATELIMIT_ENABLED, BOOLEAN).bank().security().group(SettingGroup.RATE_LIMITS)
                    .label("Rate limiting")
                    .description("Per-user limits on the expensive endpoints (gate E2).")
                    .property("modocument.ratelimit.enabled")
                    .locked("true").build(),
            builder(RATELIMIT_WINDOW, DURATION).platform().security()
                    .group(SettingGroup.RATE_LIMITS)
                    .label("Rate-limit window")
                    .description("Window shared by every per-user endpoint bucket.")
                    .property("modocument.ratelimit.window-millis", ChronoUnit.MILLIS)
                    .defaultValue("1h").range("1h", "24h").requiresRestart().build(),
            bucket(RATELIMIT_UPLOADS, "modocument.ratelimit.uploads", "Uploads",
                    "File uploads (documents, blueprints, assets).", "20", "200"),
            bucket(RATELIMIT_INGESTION, "modocument.ratelimit.ingestion", "Ingestion runs",
                    "Ingestion job create, resume, remap and revalidate.", "60", "600"),
            bucket(RATELIMIT_TRANSFORM, "modocument.ratelimit.transform", "Transforms",
                    "Document-to-table transform runs.", "30", "300"),
            bucket(RATELIMIT_EXPORTS, "modocument.ratelimit.exports", "Exports",
                    "File and database exports combined.", "60", "600"),
            bucket(RATELIMIT_DOC_GENERATE, "modocument.ratelimit.doc-generate",
                    "Document generation", "Document-creation generate runs.", "30", "300"),
            bucket(RATELIMIT_EXTRACTION, "modocument.ratelimit.extraction", "Extraction runs",
                    "Extraction job create, start and bridge.", "30", "300"),
            bucket(RATELIMIT_DSR, "modocument.ratelimit.dsr", "Data-subject requests",
                    "Data-subject search, export and erase.", "10", "100"),
            bucket(RATELIMIT_PASSWORD_CHANGE, "modocument.ratelimit.password-change",
                    "Password changes", "Password changes per user.", "5", "10"),
            bucket(RATELIMIT_MFA_CODE, "modocument.ratelimit.mfa-code", "MFA code checks",
                    "Authenticated TOTP checks (activate/disable MFA).", "10", "20"),
            builder(RATELIMIT_ADMIN_JOBS, INT).platform().security()
                    .group(SettingGroup.RATE_LIMITS)
                    .label("Manual background-job runs")
                    .description("On-demand retention purge / SoD scan runs per user and "
                            + "window (each sweeps every bank).")
                    .unit("per user per window")
                    .property("modocument.ratelimit.admin-jobs")
                    .defaultValue("10").range("1", "50").build(),
            bucket(RATELIMIT_ADMIN_SECURITY, "modocument.ratelimit.admin-security",
                    "Credential resets",
                    "Admin-issued password and MFA resets of another user.", "10", "100"),
            builder(RATELIMIT_LLM_PER_USER, INT).bank().security().group(SettingGroup.RATE_LIMITS)
                    .label("AI requests per user")
                    .description("LLM quota per user per hour.")
                    .unit("per user per hour")
                    .property("modocument.security.rate-limit.llm.per-user-max")
                    .defaultValue("60").range("1", "600").build(),
            builder(RATELIMIT_AUTH_PER_IP, INT).platform().security()
                    .group(SettingGroup.RATE_LIMITS)
                    .label("Sign-in attempts per address")
                    .description("Sign-in attempts per network address per 15 minutes.")
                    .unit("per 15 minutes")
                    .property("modocument.security.rate-limit.auth.per-ip-max")
                    .defaultValue("30").range("1", "120").build(),
            builder(RATELIMIT_AUTH_PER_EMAIL, INT).platform().security()
                    .group(SettingGroup.RATE_LIMITS)
                    .label("Sign-in attempts per account")
                    .description("Sign-in attempts per e-mail address per 15 minutes.")
                    .unit("per 15 minutes")
                    .property("modocument.security.rate-limit.auth.per-email-max")
                    .defaultValue("10").range("1", "40").build(),
            // OBS-04: per ADDRESS is only a coarse flood guard — a whole bank
            // signs in from behind one NAT, so 5/min locked out colleagues
            // verifying at the same time. The brute-force control is the
            // strict per-USER limit below (docs/settings/catalogue.md).
            builder(RATELIMIT_MFA_PER_IP, INT).platform().security()
                    .group(SettingGroup.RATE_LIMITS)
                    .label("MFA attempts per address")
                    .description("Second-factor attempts per network address per minute — a "
                            + "flood guard sized for a bank's shared NAT; the per-user limit "
                            + "is the brute-force control.")
                    .unit("per minute")
                    .property("modocument.security.rate-limit.mfa.per-ip-max")
                    .defaultValue("30").range("1", "120").build(),
            builder(RATELIMIT_MFA_PER_USER, INT).platform().security()
                    .group(SettingGroup.RATE_LIMITS)
                    .label("MFA attempts per user")
                    .description("Second-factor attempts per user per minute (the TOTP "
                            + "brute-force control; keep it strict).")
                    .unit("per minute")
                    .property("modocument.security.rate-limit.mfa.per-user-max")
                    .defaultValue("5").range("1", "20").build(),

            // ==================================================== Data protection
            builder(EXPORT_CAP_READ_WRITE, INT).bank().security()
                    .group(SettingGroup.DATA_PROTECTION)
                    .label("Export cap (read-write users)")
                    .description("Rows a read-write user may export without stating a "
                            + "reason (gate D2).")
                    .unit("rows")
                    .property("modocument.export.caps.READ_WRITE")
                    .defaultValue("5000").range("0", "50000").build(),
            builder(EXPORT_CAP_ADMIN, INT).bank().security().group(SettingGroup.DATA_PROTECTION)
                    .label("Export cap (administrators)")
                    .description("Rows an administrator may export without stating a reason.")
                    .unit("rows")
                    .property("modocument.export.caps.ADMIN")
                    .defaultValue("50000").range("0", "500000").build(),
            builder(RETENTION_ENABLED, BOOLEAN).bank().security()
                    .group(SettingGroup.DATA_PROTECTION)
                    .label("Automatic deletion of customer data")
                    .description("The daily retention purge (gate C5).")
                    .property("modocument.retention.enabled")
                    .locked("true").build(),
            builder(RETENTION_CUSTOMER_DATA_DAYS, INT).bank().security()
                    .group(SettingGroup.DATA_PROTECTION)
                    .label("Customer data retention")
                    .description("Uploaded customer data is deleted after this many days "
                            + "(gate C5 ceiling: 30).")
                    .unit("days")
                    .property("modocument.retention.customer-data-days")
                    .defaultValue("30").range("1", "30").build(),
            builder(MASKING_SCREEN_WORKING, BOOLEAN).bank().security()
                    .group(SettingGroup.DATA_PROTECTION)
                    .label("Mask PII in the editing wizard")
                    .description("Also mask PII on the owner's own working screens (exports, "
                            + "reports and read-only screens are always masked).")
                    .property("modocument.privacy.masking.screen-working")
                    .defaultValue("false").build(),
            builder(PRIVACY_TOKENIZE_PII, BOOLEAN).bank().security()
                    .group(SettingGroup.DATA_PROTECTION)
                    .label("PII tokenization in AI prompts")
                    .description("Sensitive values are replaced with placeholders before any "
                            + "prompt leaves the backend.")
                    .property("aifiles.privacy.tokenize-pii")
                    .locked("true").build(),
            builder(AI_ENABLED, BOOLEAN).bank().security().group(SettingGroup.DATA_PROTECTION)
                    .label("AI features")
                    .description("Master switch for every LLM call made for this bank; code "
                            + "extraction takes over when off.")
                    .defaultValue("true").build(),

            // ==================================================== Security alerts
            alertThreshold(ALERT_LOGIN_FAILURES_PER_USER_THRESHOLD, "login-failures-per-user",
                    "Failed sign-ins per account", "5", "20"),
            alertWindow(ALERT_LOGIN_FAILURES_PER_USER_WINDOW, "login-failures-per-user",
                    "Failed sign-ins per account: window", "5m"),
            alertThreshold(ALERT_LOGIN_FAILURES_PER_IP_THRESHOLD, "login-failures-per-ip",
                    "Failed sign-ins per address", "5", "20"),
            alertWindow(ALERT_LOGIN_FAILURES_PER_IP_WINDOW, "login-failures-per-ip",
                    "Failed sign-ins per address: window", "5m"),
            alertThreshold(ALERT_AUTHZ_DENIED_THRESHOLD, "authorization-denied-burst",
                    "Access-denied burst", "10", "40"),
            alertWindow(ALERT_AUTHZ_DENIED_WINDOW, "authorization-denied-burst",
                    "Access-denied burst: window", "5m"),
            alertThreshold(ALERT_ADMIN_BURST_THRESHOLD, "admin-action-burst",
                    "Administrator action burst", "30", "120"),
            alertWindow(ALERT_ADMIN_BURST_WINDOW, "admin-action-burst",
                    "Administrator action burst: window", "10m"),
            alertThreshold(ALERT_EXPORT_BURST_THRESHOLD, "export-burst",
                    "Export burst", "5", "20"),
            alertWindow(ALERT_EXPORT_BURST_WINDOW, "export-burst",
                    "Export burst: window", "10m"),
            alertThreshold(ALERT_PARSE_FALLBACK_SURGE_THRESHOLD, "parse-fallback-surge",
                    "Code-extraction fallback surge", "20", "80"),
            alertWindow(ALERT_PARSE_FALLBACK_SURGE_WINDOW, "parse-fallback-surge",
                    "Code-extraction fallback surge: window", "60m"),
            alertThreshold(ALERT_API_CLIENT_AUTH_FAILURES_THRESHOLD, "api-client-auth-failures",
                    "API client authentication failures", "10", "40"),
            alertWindow(ALERT_API_CLIENT_AUTH_FAILURES_WINDOW, "api-client-auth-failures",
                    "API client authentication failures: window", "5m"),
            alertThreshold(ALERT_INTEGRATION_FAILURES_THRESHOLD, "integration-failures",
                    "Integration run failures", "5", "20"),
            alertWindow(ALERT_INTEGRATION_FAILURES_WINDOW, "integration-failures",
                    "Integration run failures: window", "60m"),
            builder(ALERT_WEBHOOK_MIN_SEVERITY, ENUM).platform().security()
                    .group(SettingGroup.SECURITY_ALERTS)
                    .label("Webhook: minimum severity")
                    .description("Alerts below this severity are not pushed to the webhook "
                            + "(HIGH and CRITICAL always are).")
                    .property("modocument.alerts.webhook.min-severity")
                    .allowed("INFO", "LOW", "MEDIUM").defaultValue("INFO").build(),
            builder(ALERT_EMAIL_MIN_SEVERITY, ENUM).platform().security()
                    .group(SettingGroup.SECURITY_ALERTS)
                    .label("E-mail: minimum severity")
                    .description("Alerts below this severity are not e-mailed (HIGH and "
                            + "CRITICAL always are).")
                    .property("modocument.alerts.email.min-severity")
                    .allowed("INFO", "LOW", "MEDIUM").defaultValue("INFO").build(),

            // ==================================================== AI & extraction
            builder(EXTRACTION_DEFAULT_MODE, ENUM).bank().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("Default extraction mode")
                    .description("AI first with automatic code fallback, code only, or AI "
                            + "only; a template may choose its own.")
                    .allowed("AI_WITH_CODE_FALLBACK", "CODE_ONLY", "AI_ONLY")
                    .defaultValue("AI_WITH_CODE_FALLBACK").build(),
            builder(EXTRACTION_CODE_MIN_CONFIDENCE, DECIMAL).bank().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("Code extraction: minimum confidence")
                    .description("A code-extracted table below this score is not accepted.")
                    .defaultValue("0.75").range("0.50", "0.95").build(),
            builder(PARSING_DETERMINISTIC_ENABLED, BOOLEAN).bank().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("Deterministic parsing first")
                    .description("Parse well-structured files with code before asking the AI.")
                    .property("modocument.parsing.deterministic-enabled")
                    .defaultValue("true").build(),
            builder(PARSING_COMBINED_EXTRACTION_MAPPING, BOOLEAN).bank().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("Combined extraction and mapping")
                    .description("Extract and map columns in one AI pass.")
                    .property("modocument.parsing.combined-extraction-mapping")
                    .defaultValue("true").build(),
            builder(PARSING_EXCEL_LLM_ENABLED, BOOLEAN).bank().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("AI for Excel workbooks")
                    .description("Serialize workbooks through the AI instead of the fast "
                            + "spreadsheet parser.")
                    .property("modocument.parsing.excel.llm-enabled")
                    .defaultValue("false").build(),
            builder(MAPPING_LLM_FALLBACK_ENABLED, BOOLEAN).bank().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("AI fallback for column mapping")
                    .description("Ask the AI (column names only) about columns the code tiers "
                            + "leave unresolved.")
                    .property("mapping.llm-fallback-enabled")
                    .defaultValue("true").build(),
            builder(MAPPING_CONFIDENCE_THRESHOLD, DECIMAL).bank().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("Column mapping: minimum confidence")
                    .description("A code-tier column match below this score stays unmapped "
                            + "for review.")
                    .property("mapping.confidence-threshold")
                    .defaultValue("0.7").range("0.50", "0.95").build(),
            builder(CHAT_EDIT_MAX_CHANGE_RATIO, DECIMAL).bank().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("Chat edit: maximum changed share")
                    .description("Refuse a non-bulk AI table edit that changes more than this "
                            + "share of cells.")
                    .property("modocument.chat-edit.full-table-max-change-ratio")
                    .defaultValue("0.6").range("0.1", "1.0").build(),
            builder(DOCCREATION_SECTION_AI_ENABLED, BOOLEAN).bank().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("Document creation: AI section breakdown")
                    .description("Let the AI identify blueprint sections (heuristic parser "
                            + "otherwise).")
                    .property("modocument.doccreation.section-ai-enabled")
                    .defaultValue("true").build(),
            builder(DOCCREATION_GENERATE_AI_ENABLED, BOOLEAN).bank().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("Document creation: AI composition")
                    .description("Let the AI compose generated documents (deterministic "
                            + "engine otherwise).")
                    .property("modocument.doccreation.generate-ai-enabled")
                    .defaultValue("true").build(),
            builder(AI_CIRCUIT_FAILURE_THRESHOLD, INT).platform().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("AI circuit breaker: failures to open")
                    .description("Consecutive AI transport failures that open the circuit "
                            + "(code extraction takes over).")
                    .unit("failures")
                    .defaultValue("5").range("1", "50").build(),
            builder(AI_CIRCUIT_OPEN_DURATION, DURATION).platform().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("AI circuit breaker: open duration")
                    .description("How long the circuit stays open before a trial call.")
                    .defaultValue("120s").range("10s", "30m").build(),
            builder(PARSING_LLM_BATCH_SIZE, INT).platform().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("AI batch size")
                    .description("Rows per AI parsing batch.")
                    .unit("rows")
                    .property("modocument.parsing.llm-batch-size")
                    .defaultValue("50").range("5", "500").build(),
            builder(PARSING_LLM_BATCH_CONCURRENCY, INT).platform().operational()
                    .group(SettingGroup.AI_EXTRACTION)
                    .label("AI batch concurrency")
                    .description("AI parsing batches in flight per document.")
                    .unit("batches")
                    .property("modocument.parsing.llm-batch-concurrency")
                    .defaultValue("8").range("1", "32").build(),

            // ======================================================= Integrations
            builder(INTEGRATION_MAX_ROWS_PER_RUN, INT).bank().operational()
                    .group(SettingGroup.INTEGRATIONS)
                    .label("Maximum rows per run")
                    .description("Rows one integration run may pull or push.")
                    .unit("rows")
                    .defaultValue("50000").range("1", "500000").build(),
            builder(INTEGRATION_RUN_TIMEOUT, DURATION).bank().operational()
                    .group(SettingGroup.INTEGRATIONS)
                    .label("Run timeout")
                    .description("An integration run still busy after this long is failed.")
                    .defaultValue("15m").range("1m", "2h").build(),
            builder(INTEGRATION_MIN_SCHEDULE_INTERVAL, DURATION).bank().operational()
                    .group(SettingGroup.INTEGRATIONS)
                    .label("Minimum schedule interval")
                    .description("Shortest interval between two scheduled runs of a flow.")
                    .defaultValue("5m").range("5m", "24h").build(),
            builder(INTEGRATION_WEBHOOK_RETRY_ATTEMPTS, INT).bank().operational()
                    .group(SettingGroup.INTEGRATIONS)
                    .label("Webhook delivery attempts")
                    .description("Retries before an outbound delivery is dead-lettered.")
                    .unit("attempts")
                    .defaultValue("5").range("0", "10").build(),
            builder(INTEGRATION_OUTBOUND_PII_MASKING, BOOLEAN).bank().security()
                    .group(SettingGroup.INTEGRATIONS)
                    .label("Mask PII in outbound flows by default")
                    .description("New outbound flows mask PII-classified fields.")
                    .defaultValue("true").build(),
            builder(INTEGRATION_CLIENT_TOKEN_TTL, DURATION).bank().security()
                    .group(SettingGroup.INTEGRATIONS)
                    .label("API client token lifetime")
                    .description("Lifetime of an access token issued to a bank system.")
                    .defaultValue("15m").range("5m", "15m").build(),
            builder(INTEGRATION_ALLOW_OAUTH2, BOOLEAN).platform().security()
                    .group(SettingGroup.INTEGRATIONS)
                    .label("Allow OAuth2 client credentials")
                    .description("API clients may authenticate with OAuth2 client "
                            + "credentials.")
                    .defaultValue("true").build(),
            builder(INTEGRATION_ALLOW_API_KEYS, BOOLEAN).platform().security()
                    .group(SettingGroup.INTEGRATIONS)
                    .label("Allow API keys")
                    .description("API clients may authenticate with a static API key.")
                    .defaultValue("true").build(),
            builder(INTEGRATION_ALLOW_MTLS, BOOLEAN).platform().security()
                    .group(SettingGroup.INTEGRATIONS)
                    .label("Allow mutual TLS")
                    .description("API clients may authenticate with a client certificate.")
                    .defaultValue("true").build(),
            builder(INTEGRATION_AGENT_HEARTBEAT_TIMEOUT, DURATION).bank().operational()
                    .group(SettingGroup.INTEGRATIONS)
                    .label("Agent offline after")
                    .description("A Connector Agent silent for this long is shown offline.")
                    .defaultValue("2m").range("1m", "60m").build());

    /** A per-user endpoint bucket of modocument.ratelimit (1 permit up to 10x the default). */
    private static SettingDefinition bucket(String key, String property, String label,
                                            String description, String dflt, String max) {
        return builder(key, INT).bank().security().group(SettingGroup.RATE_LIMITS)
                .label(label).description(description)
                .unit("per user per window")
                .property(property)
                .defaultValue(dflt).range("1", max).build();
    }

    /** A windowed alert rule's threshold: 1 up to 4x the shipped default. */
    private static SettingDefinition alertThreshold(String key, String rule, String label,
                                                    String dflt, String max) {
        return builder(key, INT).platform().security().group(SettingGroup.SECURITY_ALERTS)
                .label(label + ": threshold")
                .description("Events per group within the window that raise the '" + rule
                        + "' alert.")
                .unit("events")
                .property("modocument.alerts.rules[" + rule + "].threshold")
                .defaultValue(dflt).range("1", max).build();
    }

    /** A windowed alert rule's aggregation (and dedupe) window: 1 to 60 minutes. */
    private static SettingDefinition alertWindow(String key, String rule, String label,
                                                 String dflt) {
        return builder(key, DURATION).platform().security().group(SettingGroup.SECURITY_ALERTS)
                .label(label)
                .description("Aggregation and dedupe window of the '" + rule + "' alert.")
                .property("modocument.alerts.rules[" + rule + "].window", ChronoUnit.MILLIS)
                .defaultValue(dflt).range("1m", "60m").build();
    }
}
