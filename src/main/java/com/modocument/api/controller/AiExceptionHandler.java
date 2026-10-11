package com.modocument.api.controller;

import com.modocument.infrastructure.ai.AiErrorMessages;
import com.modocument.infrastructure.ai.AiNotConfiguredException;
import com.modocument.infrastructure.ai.AiUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

/**
 * Maps Spring AI provider failures to a client-friendly error instead of an
 * unhandled 500 Internal Server Error.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiExceptionHandler {

    @ExceptionHandler(AiNotConfiguredException.class)
    public ResponseEntity<Map<String, String>> handleNotConfigured(AiNotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", e.getMessage()));
    }

    /**
     * ws23: the AI circuit breaker is open, or the bank turned AI off — 503
     * like "not configured", with a Retry-After while the circuit is open.
     */
    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleUnavailable(AiUnavailableException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        if (e.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return response.body(Map.of(
                "error", e.getMessage(),
                "code", e.reason() == null ? "AI_UNAVAILABLE" : e.reason().name()));
    }

    /**
     * The provider could not be reached at all (DNS, proxy, timeout). Spring AI
     * does not wrap this in its own exception type, so it used to surface as an
     * unmapped 500 with no hint that the failure was outbound.
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> handleUnreachable(ResourceAccessException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error",
                "The AI provider could not be reached: " + rootMessage(e)
                        + ". Check outbound network access and try again."));
    }

    static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<Map<String, String>> handleNonTransientAi(NonTransientAiException e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        String friendly = AiErrorMessages.friendlyMessage(raw);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", friendly));
    }
}
