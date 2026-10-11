package com.modocument.infrastructure.ratelimit;

import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.settings.SettingKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * ONE configurable interceptor for every expensive endpoint (ws17, SEC-007,
 * gate E2): a table of "METHOD route-pattern" → bucket, each bucket a
 * per-user {@link FixedWindowRateLimiter} sized by
 * {@link ApiRateLimitProperties} ({@code modocument.ratelimit.*}). Over-limit
 * calls throw {@link TooManyRequestsException}, which the global
 * {@code ApiExceptionHandler} turns into 429 plus a Retry-After header.
 *
 * <p><b>Route selection.</b> Registered in {@code WebConfig} for EVERY request
 * (no path patterns) and keyed on the handler's matched route pattern
 * ({@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}), not on the raw
 * URI. That is deliberate: interceptor path patterns are matched with a
 * case-SENSITIVE parser while the handler mapping is case-insensitive
 * ({@code WebConfig#configurePathMatch}), so a pattern-registered limiter
 * could be dodged with {@code /API/FILEUPLOAD/UPLOAD}. The matched pattern is
 * the declared one whatever the request's casing, path variables or matrix
 * parameters. HEAD is metered as GET (Spring MVC serves HEAD from the GET
 * handler, so an unmetered HEAD would run the full export and discard the
 * body). Methods not in the table on a covered path (e.g.
 * {@code GET /api/ingestion/jobs}) stay unmetered.
 *
 * <p><b>Who.</b> Per user (the JWT subject): every covered route requires a
 * token, and the interceptor runs after the security filter chain, so
 * {@link CurrentUserProvider} resolves. No role is exempt — see
 * {@link ApiRateLimitProperties}.
 *
 * <p><b>Overlap with the other limiters (no double counting).</b>
 * <ul>
 *   <li>LLM-spending routes ({@code /api/automapper/generate}, chat-edit, extraction
 *       infer-schema/summary) are metered by the shared per-user LLM quota
 *       ({@link LlmQuotaInterceptor} → {@link LlmRateLimiter}) and are NOT in
 *       this table.</li>
 *   <li>The legacy {@code /api/transform/transform} (and its {@code transform}
 *       bucket) was removed in 1.2.0 (ws28); the {@code ratelimit.transform}
 *       knob is kept, unused, so stored settings stay valid.</li>
 *   <li>Document generation is metered here for its rendering cost; its
 *       optional AI composition additionally draws on the LLM quota inside
 *       {@code AiDocumentComposer}, where exhaustion degrades to the
 *       deterministic engine instead of failing — two different resources,
 *       intentionally both.</li>
 *   <li>Login/refresh ({@link AuthRateLimiter}, per IP + per email) and MFA
 *       verify ({@link MfaRateLimiter}) are anonymous-phase throttles; only
 *       the authenticated credential checks — password change, MFA
 *       activate/disable — are here.</li>
 *   <li>The multipart ingestion create is an upload, but counts against the
 *       ingestion bucket only.</li>
 * </ul>
 *
 * <p><b>Editable limits (ws20).</b> Each bucket's permit count is a setting
 * ({@code ratelimit.<bucket>}, see docs/settings/catalogue.md) resolved PER
 * REQUEST for the caller's bank (the JWT bank bound in
 * {@code TenantContext}); {@code admin-jobs} is platform-wide because each
 * run sweeps every bank. With nothing stored the limit is the
 * {@code modocument.ratelimit.*} value, exactly as before. The window, the
 * key bound and the on/off switch (locked on) stay yml-only.
 */
@Component
public class ApiRateLimitInterceptor implements HandlerInterceptor {

    private final CurrentUserProvider currentUser;
    private final ApiRateLimitProperties properties;
    /** Null = the yml limits only (unit-test wiring). */
    private final SettingsReader settings;
    private final Map<String, Bucket> bucketsByRoute = new LinkedHashMap<>();
    private final Map<String, Bucket> bucketsByName = new LinkedHashMap<>();

    public ApiRateLimitInterceptor(CurrentUserProvider currentUser,
                                   ApiRateLimitProperties properties) {
        this(currentUser, properties, null);
    }

    @Autowired
    public ApiRateLimitInterceptor(CurrentUserProvider currentUser,
                                   ApiRateLimitProperties properties,
                                   SettingsReader settings) {
        this.currentUser = currentUser;
        this.properties = properties;
        this.settings = settings;

        Bucket uploads = bucket("uploads", "file upload", properties.getUploads(),
                SettingKeys.RATELIMIT_UPLOADS);
        Bucket ingestion = bucket("ingestion", "ingestion job", properties.getIngestion(),
                SettingKeys.RATELIMIT_INGESTION);
        Bucket exports = bucket("exports", "export", properties.getExports(),
                SettingKeys.RATELIMIT_EXPORTS);
        Bucket docGenerate = bucket("doc-generate", "document generation",
                properties.getDocGenerate(), SettingKeys.RATELIMIT_DOC_GENERATE);
        Bucket extraction = bucket("extraction", "extraction job", properties.getExtraction(),
                SettingKeys.RATELIMIT_EXTRACTION);
        Bucket dsr = bucket("dsr", "data-subject-rights", properties.getDsr(),
                SettingKeys.RATELIMIT_DSR);
        Bucket passwordChange = bucket("password-change", "password change",
                properties.getPasswordChange(), SettingKeys.RATELIMIT_PASSWORD_CHANGE);
        Bucket mfaCode = bucket("mfa-code", "MFA code", properties.getMfaCode(),
                SettingKeys.RATELIMIT_MFA_CODE);
        Bucket adminJobs = bucket("admin-jobs", "background job run",
                properties.getAdminJobs(), SettingKeys.RATELIMIT_ADMIN_JOBS);
        Bucket adminSecurity = bucket("admin-security", "credential reset",
                properties.getAdminSecurity(), SettingKeys.RATELIMIT_ADMIN_SECURITY);

        // File uploads: standalone data files, document assets (several
        // files per call) and .docx blueprints (parsed + section-split).
        route("POST", "/api/fileupload/upload", uploads);
        route("POST", "/api/doccreation/instances/{id}/assets", uploads);
        route("POST", "/api/doccreation/blueprints", uploads);

        // Ingestion pipeline runs: create (multipart and JSON share the
        // pattern) and every stage re-run.
        route("POST", "/api/ingestion/jobs", ingestion);
        route("POST", "/api/ingestion/jobs/{id}/resume", ingestion);
        route("POST", "/api/ingestion/jobs/{id}/remap", ingestion);
        route("POST", "/api/ingestion/jobs/{id}/revalidate", ingestion);

        // Every export path shares one bucket: the GET download, its ws18
        // POST twin (same export, over-cap reason in the body — otherwise an
        // unmetered export path) and the database push.
        route("GET", "/api/ingestion/jobs/{id}/export/file", exports);
        route("POST", "/api/ingestion/jobs/{id}/export/file", exports);
        route("POST", "/api/ingestion/jobs/{id}/export/database", exports);

        route("POST", "/api/doccreation/instances/{id}/generate", docGenerate);

        // Extraction runs: create starts the job by default (autoStart), so
        // it counts like start — otherwise the start limit is sidestepped.
        route("POST", "/api/extraction/jobs", extraction);
        route("POST", "/api/extraction/jobs/{id}/start", extraction);
        route("POST", "/api/extraction/jobs/{id}/bridge", extraction);

        route("POST", "/api/admin/dsr/search", dsr);
        route("POST", "/api/admin/dsr/export", dsr);
        route("POST", "/api/admin/dsr/erase", dsr);

        route("PUT", "/api/auth/password", passwordChange);

        // Authenticated TOTP checks (the anonymous verify has MfaRateLimiter).
        route("POST", "/api/auth/mfa/activate", mfaCode);
        route("DELETE", "/api/auth/mfa", mfaCode);

        // On-demand runs of the cross-bank sweeps (every bank per call).
        route("POST", "/api/admin/retention/run", adminJobs);
        route("POST", "/api/admin/sod/scan", adminJobs);

        // Admin-issued credential resets of another user (each one hashes a
        // password or queues a maker/checker request).
        route("POST", "/api/admin/users/{id}/password-reset", adminSecurity);
        route("POST", "/api/admin/users/{id}/mfa-reset", adminSecurity);
        // ws21 platform requests that hash temporary passwords at submit
        // (Argon2id per first user / break-glass admin).
        route("POST", "/api/platform/banks", adminSecurity);
        route("POST", "/api/platform/banks/{id}/admins", adminSecurity);
    }

    private Bucket bucket(String name, String label, int maxPermits, String settingKey) {
        Bucket bucket = new Bucket(name, label, settingKey, new FixedWindowRateLimiter(
                maxPermits, properties.getWindowMillis(), properties.getMaxKeys()));
        bucketsByName.put(name, bucket);
        return bucket;
    }

    private void route(String method, String pattern, Bucket bucket) {
        bucketsByRoute.put(method + " " + pattern, bucket);
    }

    /**
     * The route table as "METHOD pattern" → bucket name, sorted — for the
     * contract tests (every route must exist) and the generated OpenAPI
     * (which documents the 429 on exactly these operations).
     */
    public Map<String, String> routes() {
        Map<String, String> routes = new TreeMap<>();
        bucketsByRoute.forEach((route, bucket) -> routes.put(route, bucket.name()));
        return Collections.unmodifiableMap(routes);
    }

    /** Bucket name → the setting key holding its permit count (contract tests). */
    public Map<String, String> settingKeys() {
        Map<String, String> keys = new TreeMap<>();
        bucketsByName.forEach((name, bucket) -> keys.put(name, bucket.settingKey()));
        return Collections.unmodifiableMap(keys);
    }

    /**
     * The permit count of bucket {@code name} for a bank's users: the bank's
     * setting, else the platform's, else the yml value.
     */
    public int limitFor(String name, int bankId) {
        Bucket bucket = bucketsByName.get(name);
        if (bucket == null) {
            throw new IllegalArgumentException("Unknown rate-limit bucket '" + name + "'.");
        }
        return settings == null ? bucket.limiter().maxPermits()
                : settings.getInt(bucket.settingKey(), bankId);
    }

    /** The bucket name metering {@code method pattern}, or null when unmetered. */
    public String bucketFor(String method, String pattern) {
        Bucket bucket = bucketsByRoute.get(normalize(method) + " " + pattern);
        return bucket == null ? null : bucket.name();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!properties.isEnabled() || !(handler instanceof HandlerMethod)) {
            return true;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return true;
        }
        Bucket bucket = bucketsByRoute.get(normalize(request.getMethod()) + " " + pattern);
        if (bucket == null) {
            return true;
        }
        String key = "user:" + currentUser.requireUserId();
        long now = System.currentTimeMillis();
        if (!bucket.limiter().tryAcquire(key, now, currentLimit(bucket))) {
            throw new TooManyRequestsException(
                    "You have reached the " + bucket.label()
                            + " request limit for now. Please try again later.",
                    bucket.limiter().retryAfterSeconds(key, now));
        }
        return true;
    }

    /** The caller's bank's limit (TenantContext), read per request. */
    private int currentLimit(Bucket bucket) {
        return settings == null ? bucket.limiter().maxPermits()
                : settings.getIntForCurrentBank(bucket.settingKey());
    }

    /** HEAD runs the GET handler, so it spends the GET bucket. */
    private static String normalize(String method) {
        String upper = method.toUpperCase(Locale.ROOT);
        return "HEAD".equals(upper) ? "GET" : upper;
    }

    private record Bucket(String name, String label, String settingKey,
                          FixedWindowRateLimiter limiter) {
    }
}
