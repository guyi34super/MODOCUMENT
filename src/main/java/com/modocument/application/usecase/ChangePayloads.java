package com.modocument.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.model.Role;

import java.util.Locale;

/**
 * Field access for maker/checker payloads (ws10): a pending change's JSON
 * arrives from the network and comes back out of the database, so every
 * read validates shape and bounds instead of trusting it.
 */
final class ChangePayloads {

    private ChangePayloads() {
    }

    /** The persisted payload of a pending change, back as a tree. */
    static JsonNode parse(ObjectMapper mapper, String payloadJson) {
        try {
            return mapper.readTree(payloadJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The pending change payload is not valid JSON.", e);
        }
    }

    static String requireText(JsonNode payload, String field, int maxLength) {
        String value = optionalText(payload, field, maxLength);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The payload field '" + field + "' is required.");
        }
        return value;
    }

    static String optionalText(JsonNode payload, String field, int maxLength) {
        JsonNode node = payload == null ? null : payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(
                    "The payload field '" + field + "' must be a string.");
        }
        String value = node.asText();
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("The payload field '" + field
                    + "' must be at most " + maxLength + " characters.");
        }
        return value;
    }

    static int requireInt(JsonNode payload, String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        if (node == null || !node.canConvertToInt()) {
            throw new IllegalArgumentException(
                    "The payload field '" + field + "' must be an integer.");
        }
        return node.asInt();
    }

    /**
     * Same vocabulary and refusal message the ws09 endpoints used. Only the
     * four bank roles parse: PLATFORM_ADMIN and API_CLIENT are never a bank
     * user's role (ws21/ws26 assign them through their own paths).
     */
    static Role parseRole(String value) {
        Role role;
        try {
            role = Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            role = null;
        }
        if (role == null || !role.isBankRole()) {
            throw new IllegalArgumentException(
                    "Unknown role '" + value + "'; use ADMIN, APPROVER, READ_WRITE or READ_ONLY.");
        }
        return role;
    }
}
