package com.modocument.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** One model-proposed row change: 1-based row index plus only the changed cells. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RowPatch(int row, Map<String, String> values) {
}
