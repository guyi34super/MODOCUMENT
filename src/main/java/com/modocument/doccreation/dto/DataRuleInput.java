package com.modocument.doccreation.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One data field rule as the editor sends it ({@code RuleInput} of
 * docs/doccreation/data-templates-contract.md §2): the ws33 {@link RuleInput}
 * keyed by the data field's name instead of a region key. The rule's sort
 * order is its position among the rules of the same field; {@code severity}
 * defaults to ERROR, {@code origin} to MANUAL (MANUAL or IMPORTED).
 */
public record DataRuleInput(String fieldName,
                            String ruleType,
                            JsonNode config,
                            String message,
                            String severity,
                            String origin,
                            Integer sourceTemplateId,
                            String sourceFieldName) {
}
