package com.modocument.doccreation.dto;

import java.util.List;

/**
 * {@code PUT /api/doccreation/data-templates/{id}/rules}
 * (docs/doccreation/data-templates-contract.md §2): the whole rule set.
 * {@code baseRevision} is the revision the editor loaded; a stale one
 * answers 409.
 */
public record SaveDataRulesRequest(Integer baseRevision, List<DataRuleInput> rules) {
}
