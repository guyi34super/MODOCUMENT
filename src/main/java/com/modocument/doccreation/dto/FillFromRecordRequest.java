package com.modocument.doccreation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /instances/{id}/fill-from-record} (contract §4):
 * {@code linkedRecordIds} picks an alternative per linked template id,
 * {@code fields} the region keys to fill (all of the preview by default),
 * {@code overwrite} EMPTY_ONLY (default) or ALL.
 */
public record FillFromRecordRequest(@NotNull Long recordId,
                                    @Size(max = 50) Map<String, Long> linkedRecordIds,
                                    @Size(max = 1000) List<String> fields,
                                    String overwrite) {
}
