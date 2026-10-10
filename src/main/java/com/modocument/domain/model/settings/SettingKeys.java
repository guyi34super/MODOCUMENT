package com.modocument.domain.model.settings;

/**
 * Every setting key of the catalogue (ws20), as constants for the code that
 * reads them ({@code SettingsReader#getInt(SettingKeys.PASSWORD_MIN_LENGTH,
 * bankId)}). The catalogue itself — types, limits, defaults, properties —
 * is {@code infrastructure/settings/SettingDefinitions}; a test keeps the
 * two in lockstep. Full table: docs/settings/catalogue.md.
 */
public final class SettingKeys {

    private SettingKeys() {
    }

    // ---- Sign-in & passwords (SECURITY) -------------------------------------
    public static final String PASSWORD_MIN_LENGTH = "security.password.minLength";
    public static final String LOCKOUT_ALLOWED_ATTEMPTS = "security.lockout.allowedAttempts";
    public static final String LOCKOUT_BASE_MINUTES = "security.lockout.baseMinutes";
    public static final String LOCKOUT_CAP_MINUTES = "security.lockout.capMinutes";
    public static final String SESSION_IDLE_TIMEOUT = "security.session.idleTimeout";
    public static final String MFA_REQUIRE_FOR_ALL_ROLES = "security.mfa.requireForAllRoles";
    public static final String MFA_REQUIRE_FOR_ADMINS = "security.mfa.requireForAdmins";
    public static final String JWT_TTL_MINUTES = "security.jwt.ttlMinutes";

    // ---- Rate limits (SECURITY) ---------------------------------------------
    public static final String RATELIMIT_ENABLED = "ratelimit.enabled";
    public static final String RATELIMIT_WINDOW = "ratelimit.window";
    public static final String RATELIMIT_UPLOADS = "ratelimit.uploads";
    public static final String RATELIMIT_INGESTION = "ratelimit.ingestion";
    public static final String RATELIMIT_TRANSFORM = "ratelimit.transform";
    public static final String RATELIMIT_EXPORTS = "ratelimit.exports";
    public static final String RATELIMIT_DOC_GENERATE = "ratelimit.docGenerate";
    public static final String RATELIMIT_EXTRACTION = "ratelimit.extraction";
    public static final String RATELIMIT_DSR = "ratelimit.dsr";
    public static final String RATELIMIT_PASSWORD_CHANGE = "ratelimit.passwordChange";
    public static final String RATELIMIT_MFA_CODE = "ratelimit.mfaCode";
    public static final String RATELIMIT_ADMIN_JOBS = "ratelimit.adminJobs";
    public static final String RATELIMIT_ADMIN_SECURITY = "ratelimit.adminSecurity";
    public static final String RATELIMIT_LLM_PER_USER = "ratelimit.llm.perUserMax";
    public static final String RATELIMIT_AUTH_PER_IP = "ratelimit.auth.perIpMax";
    public static final String RATELIMIT_AUTH_PER_EMAIL = "ratelimit.auth.perEmailMax";
    public static final String RATELIMIT_MFA_PER_IP = "ratelimit.mfa.perIpMax";
    public static final String RATELIMIT_MFA_PER_USER = "ratelimit.mfa.perUserMax";

    // ---- Data protection (SECURITY) -----------------------------------------
    public static final String EXPORT_CAP_READ_WRITE = "export.cap.readWrite";
    public static final String EXPORT_CAP_ADMIN = "export.cap.admin";
    public static final String RETENTION_ENABLED = "retention.enabled";
    public static final String RETENTION_CUSTOMER_DATA_DAYS = "retention.customerDataDays";
    public static final String MASKING_SCREEN_WORKING = "privacy.masking.screenWorking";
    public static final String PRIVACY_TOKENIZE_PII = "privacy.tokenizePii";
    public static final String AI_ENABLED = "ai.enabled";

    // ---- Security alerts (SECURITY, platform) -------------------------------
    public static final String ALERT_LOGIN_FAILURES_PER_USER_THRESHOLD =
            "alerts.loginFailuresPerUser.threshold";
    public static final String ALERT_LOGIN_FAILURES_PER_USER_WINDOW =
            "alerts.loginFailuresPerUser.window";
    public static final String ALERT_LOGIN_FAILURES_PER_IP_THRESHOLD =
            "alerts.loginFailuresPerIp.threshold";
    public static final String ALERT_LOGIN_FAILURES_PER_IP_WINDOW =
            "alerts.loginFailuresPerIp.window";
    public static final String ALERT_AUTHZ_DENIED_THRESHOLD =
            "alerts.authorizationDeniedBurst.threshold";
    public static final String ALERT_AUTHZ_DENIED_WINDOW = "alerts.authorizationDeniedBurst.window";
    public static final String ALERT_ADMIN_BURST_THRESHOLD = "alerts.adminActionBurst.threshold";
    public static final String ALERT_ADMIN_BURST_WINDOW = "alerts.adminActionBurst.window";
    public static final String ALERT_EXPORT_BURST_THRESHOLD = "alerts.exportBurst.threshold";
    public static final String ALERT_EXPORT_BURST_WINDOW = "alerts.exportBurst.window";
    public static final String ALERT_PARSE_FALLBACK_SURGE_THRESHOLD =
            "alerts.parseFallbackSurge.threshold";
    public static final String ALERT_PARSE_FALLBACK_SURGE_WINDOW =
            "alerts.parseFallbackSurge.window";
    public static final String ALERT_API_CLIENT_AUTH_FAILURES_THRESHOLD =
            "alerts.apiClientAuthFailures.threshold";
    public static final String ALERT_API_CLIENT_AUTH_FAILURES_WINDOW =
            "alerts.apiClientAuthFailures.window";
    /** ws25 (SE4): the integration-failures windowed alert rule. */
    public static final String ALERT_INTEGRATION_FAILURES_THRESHOLD =
            "alerts.integrationFailures.threshold";
    public static final String ALERT_INTEGRATION_FAILURES_WINDOW =
            "alerts.integrationFailures.window";
    public static final String ALERT_WEBHOOK_MIN_SEVERITY = "alerts.webhook.minSeverity";
    public static final String ALERT_EMAIL_MIN_SEVERITY = "alerts.email.minSeverity";

    // ---- AI & extraction (OPERATIONAL) --------------------------------------
    public static final String EXTRACTION_DEFAULT_MODE = "extraction.defaultMode";
    public static final String EXTRACTION_CODE_MIN_CONFIDENCE = "extraction.code.minConfidence";
    public static final String PARSING_DETERMINISTIC_ENABLED = "parsing.deterministicEnabled";
    public static final String PARSING_COMBINED_EXTRACTION_MAPPING =
            "parsing.combinedExtractionMapping";
    public static final String PARSING_EXCEL_LLM_ENABLED = "parsing.excel.llmEnabled";
    public static final String PARSING_LLM_BATCH_SIZE = "parsing.llmBatchSize";
    public static final String PARSING_LLM_BATCH_CONCURRENCY = "parsing.llmBatchConcurrency";
    public static final String MAPPING_LLM_FALLBACK_ENABLED = "mapping.llmFallbackEnabled";
    public static final String MAPPING_CONFIDENCE_THRESHOLD = "mapping.confidenceThreshold";
    public static final String CHAT_EDIT_MAX_CHANGE_RATIO = "chatEdit.fullTableMaxChangeRatio";
    public static final String DOCCREATION_SECTION_AI_ENABLED = "doccreation.sectionAiEnabled";
    public static final String DOCCREATION_GENERATE_AI_ENABLED = "doccreation.generateAiEnabled";
    public static final String AI_CIRCUIT_FAILURE_THRESHOLD = "ai.circuitBreaker.failureThreshold";
    public static final String AI_CIRCUIT_OPEN_DURATION = "ai.circuitBreaker.openDuration";

    // ---- Integrations (ws25/ws26 consumers; Wave 3) ----------------------
    public static final String INTEGRATION_MAX_ROWS_PER_RUN = "integration.maxRowsPerRun";
    public static final String INTEGRATION_RUN_TIMEOUT = "integration.runTimeout";
    public static final String INTEGRATION_MIN_SCHEDULE_INTERVAL = "integration.minScheduleInterval";
    public static final String INTEGRATION_WEBHOOK_RETRY_ATTEMPTS =
            "integration.webhookRetryAttempts";
    public static final String INTEGRATION_OUTBOUND_PII_MASKING = "integration.outboundPiiMasking";
    public static final String INTEGRATION_CLIENT_TOKEN_TTL = "integration.clientTokenTtl";
    public static final String INTEGRATION_ALLOW_OAUTH2 = "integration.auth.allowOauth2";
    public static final String INTEGRATION_ALLOW_API_KEYS = "integration.auth.allowApiKeys";
    public static final String INTEGRATION_ALLOW_MTLS = "integration.auth.allowMtls";
    public static final String INTEGRATION_AGENT_HEARTBEAT_TIMEOUT =
            "integration.agentHeartbeatTimeout";
}
