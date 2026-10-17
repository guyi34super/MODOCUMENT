package com.modocument.doccreation.dto;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/doccreation/data-templates/{id}/rules/test}
 * (docs/doccreation/data-templates-contract.md §2): sample values by field
 * name, checked against {@code rules} when present (unsaved rules) or else
 * the saved rules. Nothing is stored.
 */
public record TestDataRulesRequest(Map<String, String> values, List<DataRuleInput> rules) {
}
