package com.modocument.doccreation.dto;

import java.util.List;

/**
 * {@code PUT /blueprints/{id}/rules}: the template's whole rule set
 * (field-validation-contract §3). {@code baseRevision} is the revision the
 * editor loaded; a stale one answers 409.
 */
public record SaveRulesRequest(Integer baseRevision, List<RuleInput> rules) {
}
