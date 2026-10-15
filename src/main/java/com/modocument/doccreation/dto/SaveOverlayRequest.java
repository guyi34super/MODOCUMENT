package com.modocument.doccreation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * JSON body of {@code PUT /api/doccreation/blueprints/{id}/overlay}
 * (contract §5): the reviewed or edited overlay. {@code baseVersion} is the
 * version the editor started from (stale: 409). {@code confirm} (default
 * true) turns a DRAFT into version 1 READY; false saves the draft without
 * confirming it. On a READY template every save is a new version.
 */
public record SaveOverlayRequest(Integer baseVersion,
                                 @Valid @Size(max = 2000) List<RegionDto> regions,
                                 @Valid @Size(max = 2000) List<EditDto> edits,
                                 @Size(max = 500) String note,
                                 Boolean confirm) {
}
