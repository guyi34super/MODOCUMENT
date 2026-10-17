package com.modocument.doccreation.dto;

import java.util.List;

/**
 * {@code POST /api/doccreation/data-templates/{id}/add-from-flow-templates}
 * (docs/doccreation/data-templates-contract.md §2, "Building from several
 * flow templates"): adds the chosen flow fields and their rules to an
 * existing data template. A stale {@code baseRevision} is a 409; a missing
 * one skips the check, as in every other save.
 */
public record AddFlowTemplatesRequest(Integer baseRevision, List<CopyFlowTemplatesRequest.Source> sources) {
}
