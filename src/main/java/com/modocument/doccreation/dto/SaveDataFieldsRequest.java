package com.modocument.doccreation.dto;

import java.util.List;

/**
 * {@code PUT /api/doccreation/data-templates/{id}/fields}
 * (docs/doccreation/data-templates-contract.md §2): the whole field set.
 * {@code baseRevision} is the revision the editor loaded; a stale one
 * answers 409.
 */
public record SaveDataFieldsRequest(Integer baseRevision, List<DataFieldInput> fields) {
}
