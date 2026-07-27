package com.modocument.application.dto;

public record RowValidationResult(int row, String fieldName, String rawValue, String errorMessage,
                                  String ruleType) {

    public RowValidationResult(int row, String fieldName, String rawValue) {
        this(row, fieldName, rawValue, null, null);
    }

    public RowValidationResult(int row, String fieldName, String rawValue, String errorMessage) {
        this(row, fieldName, rawValue, errorMessage, null);
    }
}
