package com.modocument.doccreation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code POST /blueprints/{id}/sources} (contract §4). */
public record CreateBlueprintSourceRequest(@NotNull Integer templateId,
                                           @Size(max = 16) String role,
                                           @Size(max = 200) String linkFieldPrimary,
                                           @Size(max = 200) String linkFieldThis) {
}
