package com.modocument.infrastructure.ratelimit;

import com.modocument.application.port.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * ONE configurable interceptor for every expensive endpoint (ws17, SEC-007,
 * gate E2): maps "METHOD route-pattern" → bucket, each bucket a per-user
 * {@link FixedWindowRateLimiter} sized by {@link ApiRateLimitProperties}.
 * Over-limit calls throw {@link TooManyRequestsException}, which the global
 * {@code ApiExceptionHandler} turns into 429 plus a Retry-After header.
 *
 * <p>Registered in {@code WebConfig} against {@link #pathPatterns()}. Runs
 * after the security filter chain, so the caller is authenticated and
 * {@link CurrentUserProvider} resolves — all covered routes require a token.
 * The matched route pattern (not the raw URI) selects the bucket, so path
 * variables and the case-insensitive matcher cannot split or dodge a bucket.
 *
 * <p>No admin exemption — see {@link ApiRateLimitProperties}. The LLM-spending
 * endpoints have their own quota ({@code LlmQuotaInterceptor}); auth
 * login/MFA throttles live in {@code AuthRateLimiter}/{@code MfaRateLimiter}.
 */
@Component
public class ApiRateLimitInterceptor implements HandlerInterceptor {

    private final CurrentUserProvider currentUser;
    private final ApiRateLimitProperties properties;
    private final Map<String, Bucket> bucketsByRoute = new LinkedHashMap<>();

    public ApiRateLimitInterceptor(CurrentUserProvider currentUser,
                                   ApiRateLimitProperties properties) {
        this.currentUser = currentUser;
        this.properties = properties;

        Bucket uploads = bucket("uploads", "file upload", properties.getUploads());
        Bucket ingestion = bucket("ingestion", "ingestion job", properties.getIngestion());
        Bucket transform = bucket("transform", "transform", properties.getTransform());
        Bucket exports = bucket("exports", "export", properties.getExports());
        Bucket docGenerate = bucket("doc-generate", "document generation",
                properties.getDocGenerate());
        Bucket extraction = bucket("extraction", "extraction job", properties.getExtraction());
        Bucket dsr = bucket("dsr", "data-subject-rights", properties.getDsr());
        Bucket passwordChange = bucket("password-change", "password change",
                properties.getPasswordChange());

        route("POST", "/api/fileupload/upload", uploads);
        route("POST", "/api/doccreation/instances/{id}/assets", uploads);

        route("POST", "/api/ingestion/jobs", ingestion);
        route("POST", "/api/ingestion/jobs/{id}/resume", ingestion);
        route("POST", "/api/ingestion/jobs/{id}/remap", ingestion);
        route("POST", "/api/ingestion/jobs/{id}/revalidate", ingestion);

        route("POST", "/api/transform/transform", transform);

        route("GET", "/api/ingestion/jobs/{id}/export/file", exports);
        route("POST", "/api/ingestion/jobs/{id}/export/database", exports);

        route("POST", "/api/doccreation/instances/{id}/generate", docGenerate);

        route("POST", "/api/extraction/jobs/{id}/start", extraction);
        route("POST", "/api/extraction/jobs/{id}/bridge", extraction);

        route("POST", "/api/admin/dsr/search", dsr);
        route("POST", "/api/admin/dsr/export", dsr);
        route("POST", "/api/admin/dsr/erase", dsr);

        route("PUT", "/api/auth/password", passwordChange);
    }

    private Bucket bucket(String name, String label, int maxPermits) {
        return new Bucket(name, label, new FixedWindowRateLimiter(
                maxPermits, properties.getWindowMillis(), properties.getMaxKeys()));
    }

    private void route(String method, String pattern, Bucket bucket) {
        bucketsByRoute.put(method + " " + pattern, bucket);
    }

    /** The distinct route patterns this interceptor covers, for registration. */
    public String[] pathPatterns() {
        Set<String> patterns = new TreeSet<>();
        for (String route : bucketsByRoute.keySet()) {
            patterns.add(route.substring(route.indexOf(' ') + 1));
        }
        return patterns.toArray(String[]::new);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!properties.isEnabled()) {
            return true;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return true;
        }
        Bucket bucket = bucketsByRoute.get(request.getMethod().toUpperCase() + " " + pattern);
        if (bucket == null) {
            // Same path, uncovered method (e.g. GET /api/ingestion/jobs).
            return true;
        }
        String key = bucket.name + ":user:" + currentUser.requireUserId();
        long now = System.currentTimeMillis();
        if (!bucket.limiter.tryAcquire(key, now)) {
            throw new TooManyRequestsException(
                    "You have reached the " + bucket.label
                            + " request limit for now. Please try again later.",
                    bucket.limiter.retryAfterSeconds(key, now));
        }
        return true;
    }

    private record Bucket(String name, String label, FixedWindowRateLimiter limiter) {
    }
}
