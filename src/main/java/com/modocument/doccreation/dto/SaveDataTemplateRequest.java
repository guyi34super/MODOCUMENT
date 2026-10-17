package com.modocument.doccreation.dto;

/**
 * {@code POST /api/doccreation/data-templates} and {@code PUT /{id}}
 * (docs/doccreation/data-templates-contract.md §2): the name (1-200
 * characters) and an optional description (at most 1000).
 */
public record SaveDataTemplateRequest(String name, String description) {
}
