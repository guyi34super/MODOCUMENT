package com.modocument.infrastructure.ratelimit;

import com.modocument.application.port.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * Applies the per-user {@link LlmRateLimiter} to the LLM-spending endpoints
 * (registered against {@link #PATHS} in {@code WebConfig}). Runs after the
 * security filter chain, so the caller is already authenticated and
 * {@link CurrentUserProvider} resolves. Throwing here is handled by the global
 * {@code ApiExceptionHandler} exactly as a controller-thrown exception would be.
 */
@Component
public class LlmQuotaInterceptor implements HandlerInterceptor {

    /**
     * Every endpoint that spends LLM tokens on each call (F-007). chat-edit
     * and the extraction AI helpers carry an id in the path, hence the
     * wildcard segment. {@code WebConfig} registers these with the same
     * case-insensitive parser the handler mapping uses (ws17) — with Spring's
     * default case-sensitive interceptor matching, {@code /API/AIFILES/SEND}
     * reached the handler but skipped the quota. All are POST-or-GET-only
     * routes with no cheap sibling method, so path matching suffices.
     */
    public static final List<String> PATHS = List.of(
            "/api/automapper/generate",
            "/api/ingestion/jobs/*/chat-edit",
            // ws17: these two were unmetered LLM calls.
            "/api/extraction/jobs/*/infer-schema",
            "/api/extraction/jobs/*/summary");

    private final CurrentUserProvider currentUser;
    private final LlmRateLimiter limiter;

    public LlmQuotaInterceptor(CurrentUserProvider currentUser, LlmRateLimiter limiter) {
        this.currentUser = currentUser;
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        limiter.check(currentUser.requireUserId());
        return true;
    }
}
