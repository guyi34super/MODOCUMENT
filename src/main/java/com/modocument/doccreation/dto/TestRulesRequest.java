package com.modocument.doccreation.dto;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /blueprints/{id}/rules/test} (field-validation-contract §3):
 * sample values by region key, checked against {@code rules} when present
 * (unsaved rules) or else the saved rules. Nothing is stored.
 */
public record TestRulesRequest(Map<String, String> values, List<RuleInput> rules) {
}
