package com.modocument.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Structured-output shape for patch-mode chat edits. An object root (rather
 * than a bare array) is followed far more reliably by the various models an
 * OpenAI-compatible endpoint may serve.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatEditPatchResponse(List<RowPatch> rows) {
}
