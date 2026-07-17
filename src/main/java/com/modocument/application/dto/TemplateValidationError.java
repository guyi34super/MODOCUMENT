package com.modocument.application.dto;

/** One problem found while validating a template definition (400 payload item). */
public record TemplateValidationError(String fieldName, String ruleType, String message) {
}
