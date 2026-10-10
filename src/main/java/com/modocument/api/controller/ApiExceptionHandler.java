package com.modocument.api.controller;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import com.modocument.infrastructure.ratelimit.TooManyRequestsException;
import com.modocument.infrastructure.job.JobWaitTimeoutException;
import com.modocument.infrastructure.security.PasswordChangeRequiredGate;
import com.modocument.infrastructure.security.RequestIdFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Last-resort translation of infrastructure failures into answers a user can
 * act on. Without this, a wrong export password, an unreachable database, an
 * oversized upload and a genuine bug all reached the browser as the same bare
 * "Internal Server Error".
 *
 * <p>Ordered last on purpose: handlers for specific concerns (AI provider
 * failures, lost optimistic locks) must win over these.
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final PasswordChangeRequiredGate passwordChangeGate;

    public ApiExceptionHandler(PasswordChangeRequiredGate passwordChangeGate) {
        this.passwordChangeGate = passwordChangeGate;
    }

    /**
     * Method-security denials normally pass through to the security filter
     * chain's plain 403 (see {@link #unexpected}). The ONE denial that gets
     * a distinguishable body is the ws09 forced password change: the SPA
     * needs to tell "you lack this permission" apart from "change your
     * temporary password first", so when the denied caller carries the
     * MustChangePassword flag the 403 says so with a stable machine code.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<?> accessDenied(
            org.springframework.security.access.AccessDeniedException e) throws Exception {
        if (passwordChangeGate.callerMustChange()) {
            String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "You must change your password before using the application.");
            body.put("code", "PASSWORD_CHANGE_REQUIRED");
            if (requestId != null) {
                body.put("requestId", requestId);
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        throw e;
    }

    /**
     * The three exception types the use cases throw. Controllers that map them
     * locally still win; this is for the endpoints that had no handler at all
     * and answered 500 to what was really a bad request.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException e) {
        return error(HttpStatus.NOT_FOUND, message(e, "Not found."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, message(e, "The request could not be processed."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, message(e, "The request conflicts with the current state."));
    }

    /**
     * Rate limits (SEC-022): 429 plus a {@code Retry-After} header. The
     * throwing limiter knows how much of its fixed window remains, so the
     * header tells well-behaved clients exactly how long to back off instead
     * of leaving them to guess-and-hammer.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<?> tooManyRequests(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(errorBody(message(e, "Too many requests. Please slow down and try again.")));
    }

    @ExceptionHandler(JobWaitTimeoutException.class)
    public ResponseEntity<?> jobWaitTimeout(JobWaitTimeoutException e) {
        return error(HttpStatus.REQUEST_TIMEOUT, message(e, "The job did not finish in time."));
    }

    /**
     * A malformed body (bad JSON, wrong types) or a bad path/query type is the
     * caller's mistake, not a server fault. Answer 400 with a generic message
     * rather than letting the catch-all turn it into a 500 that echoes Jackson
     * or binding internals back to the client.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> malformedRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST,
                "The request could not be read: check that the body is valid JSON and the "
                        + "fields have the expected types.");
    }

    /**
     * Bean Validation on a {@code @Valid} request body (C6): 400 with a
     * field→message map. Only the declared constraint messages travel — never
     * the rejected values, which may be the very PII the constraint caught.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> invalidRequestBody(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        e.getBindingResult().getGlobalErrors().forEach(
                error -> fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Validation failed.", "fields", fields));
    }

    /** Bean Validation on method parameters (@Validated): same 400 shape. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> invalidRequestParameters(ConstraintViolationException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(violation -> {
            String path = String.valueOf(violation.getPropertyPath());
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fields.putIfAbsent(field, violation.getMessage());
        });
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Validation failed.", "fields", fields));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE,
                "That file is larger than the upload limit. Split it or raise "
                        + "spring.servlet.multipart.max-file-size.");
    }

    /**
     * A violated database constraint (duplicate key, broken FK, CHECK): 409
     * with a generic message. The raw driver text is logged but NEVER echoed —
     * PostgreSQL spells out the offending VALUES, which can be PII. The
     * requestId lets support correlate the answer with the logged cause.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> dataConflict(DataIntegrityViolationException e) {
        log.warn("Database integrity violation: {}", rootMessage(e));
        return error(HttpStatus.CONFLICT, "The request conflicts with existing data.");
    }

    /**
     * Other database trouble — host down, bad SQL, unusable credentials. The
     * raw driver message can carry SQL fragments and parameter values, so the
     * client gets a generic answer plus the requestId; the detail is in the
     * server log under the same id.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> databaseProblem(DataAccessException e) {
        if (e instanceof DataAccessResourceFailureException) {
            log.warn("Database unreachable: {}", rootMessage(e));
            return error(HttpStatus.BAD_GATEWAY, "The database could not be reached.");
        }
        log.error("Database request failed", e);
        return error(HttpStatus.BAD_GATEWAY, "The database request failed.");
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<?> ioProblem(UncheckedIOException e) {
        // The root message is a file-system path — server internals, logged
        // but not echoed.
        log.error("File system failure", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "A file could not be read or written.");
    }

    /**
     * Anything still unhandled. Spring's own web exceptions already carry a
     * correct status, so they are rethrown for the framework's resolver rather
     * than flattened into a 500. Everything else answers a generic message —
     * the root cause of an arbitrary failure can quote SQL, paths or payload
     * data — plus the requestId, so support can correlate with the logged
     * stack trace (every log line of the request carries the same id).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception e) throws Exception {
        if (e instanceof ErrorResponse) {
            throw e;
        }
        // Method-security denials (@RequiresPermission) and authentication
        // failures must reach the security filter chain's translation into
        // 403/401 — flattening them into a 500 would hide every RBAC denial.
        if (e instanceof org.springframework.security.access.AccessDeniedException
                || e instanceof org.springframework.security.core.AuthenticationException) {
            throw e;
        }
        log.error("Unhandled failure", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong.");
    }

    private static String message(Throwable e, String fallback) {
        return e.getMessage() == null || e.getMessage().isBlank() ? fallback : e.getMessage();
    }

    /**
     * {"error": message, "requestId": id} — the id (RequestIdFilter's MDC key,
     * echoed in the X-Request-Id header too) is what support greps the logs
     * for; the message itself stays free of internals.
     */
    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(errorBody(message));
    }

    private static Map<String, String> errorBody(String message) {
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        return requestId == null
                ? Map.of("error", message)
                : Map.of("error", message, "requestId", requestId);
    }

    /** Innermost message — the outer wrapper rarely says anything useful. */
    static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
    }
}
