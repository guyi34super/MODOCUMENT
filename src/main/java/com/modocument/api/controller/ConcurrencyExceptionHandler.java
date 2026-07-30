package com.modocument.api.controller;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

/**
 * Translates a lost optimistic-lock race into a 409 the UI can explain,
 * instead of an unhandled 500. Hibernate raises this when the row was changed
 * by another writer between load and save — the caller's copy is stale, so the
 * only safe advice is "reload and redo".
 */
@ControllerAdvice
public class ConcurrencyExceptionHandler {

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleStaleWrite(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                "This job was changed by another request while you were working on it. "
                        + "Reload the job and apply your change again."));
    }
}
