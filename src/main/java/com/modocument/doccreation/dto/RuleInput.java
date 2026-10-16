package com.modocument.doccreation.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One field rule as the editor sends it (field-validation-contract §3,
 * {@code PUT /blueprints/{id}/rules} and {@code POST /rules/test}). The
 * rule's sort order is its position among the rules of the same field.
 * {@code severity} defaults to ERROR, {@code origin} to MANUAL.
 */
public record RuleInput(String regionKey,
                        String ruleType,
                        JsonNode config,
                        String message,
                        String severity,
                        String origin,
                        Integer sourceTemplateId,
                        String sourceFieldName) {
}
