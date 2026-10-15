package com.modocument.doccreation.dto;

import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * {@code PUT /blueprints/{id}/sources/{sourceId}} (contract §4): the whole
 * mapping (region key to template field name; a null or blank field leaves
 * the region unmapped) and, for a LINKED source, its link fields (null keeps
 * the saved ones).
 */
public record UpdateBlueprintSourceRequest(@Size(max = 500) Map<String, String> mapping,
                                           @Size(max = 200) String linkFieldPrimary,
                                           @Size(max = 200) String linkFieldThis) {
}
