package com.modocument.doccreation.dto;

import java.util.Map;

/**
 * {@code POST /blueprints/{id}/rules/import} (field-validation-contract §3):
 * without {@code templateId} the rules come from the template's saved data
 * sources ({@code mapping} may narrow them); with one, from that flow
 * template ({@code mapping}, region key to field name, replaces the
 * auto-match). {@code mode} is MERGE (default) or REPLACE.
 */
public record ImportRulesRequest(Integer baseRevision,
                                 Integer templateId,
                                 Map<String, String> mapping,
                                 String mode) {
}
