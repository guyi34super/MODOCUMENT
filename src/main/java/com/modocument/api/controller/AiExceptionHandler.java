package com.modocument.api.controller;

import com.modocument.infrastructure.ai.AiNotConfiguredException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps Spring AI provider failures to a client-friendly error instead of an
 * unhandled 500 Internal Server Error.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiExceptionHandler {

    private static final Pattern JSON_MESSAGE =
            Pattern.compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    @ExceptionHandler(AiNotConfiguredException.class)
    public ResponseEntity<Map<String, String>> handleNotConfigured(AiNotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", e.getMessage()));
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
        String friendly = friendlyMessage(raw);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", friendly));
    }

    static String friendlyMessage(String raw) {
        if (raw.contains("402") || raw.toLowerCase().contains("credits")) {
            return "AI provider credits are insufficient. Add credits to your OpenRouter account "
                    + "or ask for a smaller edit.";
        }
        if (raw.contains("401") || raw.toLowerCase().contains("unauthorized")) {
            return "AI provider rejected the API key. Check AIFILES_OPENAI_APIKEY in your environment.";
        }
        Matcher matcher = JSON_MESSAGE.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        }
        if (raw.startsWith("HTTP ")) {
            int dash = raw.indexOf(" - ");
            if (dash > 0 && dash + 3 < raw.length()) {
                return raw.substring(dash + 3);
            }
        }
        return raw.isBlank() ? "AI provider request failed." : raw;
    }
}
