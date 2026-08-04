package com.modocument.application.dto;

/** Text extracted from a document plus which reader produced it. */
public record DocumentExtractionResult(String text, String extractionSource) {
}
