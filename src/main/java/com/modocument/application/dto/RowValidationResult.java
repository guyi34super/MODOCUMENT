package com.modocument.application.dto;

public record RowValidationResult(int row, String fieldName, String rawValue, String errorMessage) {

    public RowValidationResult(int row, String fieldName, String rawValue) {
        this(row, fieldName, rawValue, null);
    }
}
