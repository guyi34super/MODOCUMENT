package com.modocument.api.controller;

import org.springframework.ai.retry.NonTransientAiException;
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
