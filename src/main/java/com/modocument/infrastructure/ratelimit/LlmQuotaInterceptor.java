package com.modocument.infrastructure.ratelimit;

import com.modocument.application.port.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the per-user {@link LlmRateLimiter} to the LLM-spending endpoints
 * (registered against their paths in {@code WebConfig}). Runs after the
 * security filter chain, so the caller is already authenticated and
 * {@link CurrentUserProvider} resolves. Throwing here is handled by the global
 * {@code ApiExceptionHandler} exactly as a controller-thrown exception would be.
 */
@Component
public class LlmQuotaInterceptor implements HandlerInterceptor {

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
