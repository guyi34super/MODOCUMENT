package com.modocument.domain.model;

public record ValidationError(int row, String fieldName, String rawValue, String errorMessage) {
}
