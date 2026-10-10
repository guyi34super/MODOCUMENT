package com.modocument.api.controller;

import com.modocument.domain.model.settings.SettingException;
import com.modocument.infrastructure.security.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings refusals (ws20) in the ApiExceptionHandler style, with the
 * stable machine codes of docs/phase2/contracts.md §3:
 * {@code {error, code, message, key, requestId}} — 400 for
 * SETTING_OUT_OF_RANGE / SETTING_LOCKED / SETTING_UNKNOWN, 409 for
 * SETTING_VERSION_CONFLICT / SETTING_CHANGE_PENDING / USE_PLATFORM_SETTINGS.
 * Global (not controller-local) because a SETTING_VERSION_CONFLICT also
 * surfaces from the maker/checker approve route when an approved change can
 * no longer apply. Ordered ahead of the catch-all ApiExceptionHandler.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SettingsExceptionHandler {

    @ExceptionHandler(SettingException.class)
    public ResponseEntity<Map<String, Object>> refused(SettingException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("code", e.code().name());
        body.put("message", e.getMessage());
        if (e.key() != null) {
            body.put("key", e.key());
        }
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return ResponseEntity.status(e.code().httpStatus()).body(body);
    }
}
