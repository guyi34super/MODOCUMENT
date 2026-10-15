package com.modocument.doccreation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * JSON body of {@code PUT /api/doccreation/instances/{id}/values}: the keys
 * to change, MERGED into the stored values (an empty string or null clears
 * a key; keys not sent stay as they are; an unknown key is a 400).
 */
public record UpdateValuesRequest(@NotNull @Size(max = 2000) Map<String, String> values) {
}
