package com.modocument.api.controller;

import com.modocument.application.usecase.IntegrationApiException;
import com.modocument.infrastructure.export.ExportCapExceededException;
import com.modocument.infrastructure.ratelimit.TooManyRequestsException;
import com.modocument.infrastructure.security.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Errors of the machine API (ws26) in ONE shape a bank system can branch on:
 * {@code {error, code, message, requestId}} with a stable {@code code}
 * (docs/integration/developer-guide.md). Scoped to the integration
 * controller and ordered first, so the UI-facing handlers keep their shapes.
 */
@RestControllerAdvice(assignableTypes = IntegrationApiController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IntegrationApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IntegrationApiExceptionHandler.class);

    @ExceptionHandler(IntegrationApiException.class)
    public ResponseEntity<Map<String, Object>> api(IntegrationApiException e) {
        return error(HttpStatus.valueOf(e.status()), e.code(), e.getMessage());
    }

    /** A missing scope (method security): 403, never a hint about other routes. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        return error(HttpStatus.FORBIDDEN, "INSUFFICIENT_SCOPE",
                "This API client's scopes do not allow this operation.");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", message(e, "Not found."));
    }

    @ExceptionHandler(ExportCapExceededException.class)
    public ResponseEntity<Map<String, Object>> capExceeded(ExportCapExceededException e) {
        ResponseEntity<Map<String, Object>> response = error(HttpStatus.FORBIDDEN,
                ExportCapExceededException.CODE, e.getMessage());
        response.getBody().put("cap", e.cap());
        response.getBody().put("rows", e.rows());
        return response;
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, Object>> tooMany(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body("RATE_LIMITED", message(e, "Too many requests.")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "The upload exceeds the size limit.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, Object>> malformed(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "The request is missing a parameter or has one of the wrong type.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Unsupported Content-Type for this operation.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                message(e, "The request could not be processed."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, "CONFLICT",
                message(e, "The request conflicts with the current state."));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> unexpected(RuntimeException e) {
        log.error("Integration API failure", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong.");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code,
                                                             String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(body(code, message));
    }

    private static Map<String, Object> body(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", code);
        body.put("message", message);
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }

    private static String message(Throwable e, String fallback) {
        return e.getMessage() == null || e.getMessage().isBlank() ? fallback : e.getMessage();
    }
}
