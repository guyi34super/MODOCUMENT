package com.modocument.doccreation.api;

import com.modocument.doccreation.usecase.DocCreationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The document-creation error mapping, shared by its controllers: unknown or
 * foreign id 404, wrong state 409, bad input 400, and a
 * {@link DocCreationException} with its own status and {@code code}
 * (410 LEGACY_TEMPLATE, 400 NOT_A_PDF / ENCRYPTED / TOO_MANY_PAGES,
 * 500 EXACTNESS_FAILED, 503 ENGINE_UNAVAILABLE).
 */
final class DocCreationErrors {

    private DocCreationErrors() {
    }

    static ResponseEntity<Map<String, Object>> of(DocCreationException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("code", e.code());
        return ResponseEntity.status(e.status()).body(body);
    }

    static ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    static ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    static ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** {@code attachment; filename="…"} with the quote stripped from the (sanitized) name. */
    static String attachment(String name) {
        return "attachment; filename=\"" + name.replace("\"", "") + "\"";
    }
}
