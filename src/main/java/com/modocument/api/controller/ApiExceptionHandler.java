package com.modocument.api.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.UncheckedIOException;
import java.util.Map;

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

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE,
                "That file is larger than the upload limit. Split it or raise "
                        + "spring.servlet.multipart.max-file-size.");
    }

    /**
     * Database trouble is either the caller's (a bad target table, a
     * constraint, unusable credentials) or the environment's (host down). The
     * first is a 400, the second a 502 — never an opaque 500.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> databaseProblem(DataAccessException e) {
        String detail = rootMessage(e);
        if (e instanceof DataAccessResourceFailureException) {
            log.warn("Database unreachable: {}", detail);
            return error(HttpStatus.BAD_GATEWAY,
                    "The database could not be reached: " + detail);
        }
        if (e instanceof DataIntegrityViolationException
                || e instanceof InvalidDataAccessApiUsageException
                || e instanceof InvalidDataAccessResourceUsageException) {
            log.warn("Database rejected the request: {}", detail);
            return error(HttpStatus.BAD_REQUEST, "The database rejected the request: " + detail);
        }
        log.error("Unexpected database failure", e);
        return error(HttpStatus.BAD_GATEWAY, "The database request failed: " + detail);
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<?> ioProblem(UncheckedIOException e) {
        log.error("File system failure", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "A file could not be read or written: " + rootMessage(e));
    }

    /**
     * Anything still unhandled. Spring's own web exceptions already carry a
     * correct status, so they are rethrown for the framework's resolver rather
     * than flattened into a 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception e) throws Exception {
        if (e instanceof ErrorResponse) {
            throw e;
        }
        log.error("Unhandled failure", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong: " + rootMessage(e));
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
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
