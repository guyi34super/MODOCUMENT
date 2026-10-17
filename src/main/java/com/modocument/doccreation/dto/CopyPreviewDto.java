package com.modocument.doccreation.dto;

import java.util.List;

/**
 * {@code GET /api/doccreation/data-templates/copy-preview?templateId=}
 * (docs/doccreation/data-templates-contract.md §2): what copying a flow
 * template into a new data template would create, per flow field: its data
 * type, the data field kind it becomes, its PII kind, the rules it brings
 * and the flow rules that would be skipped (with the reason).
 */
public record CopyPreviewDto(int templateId,
                             String templateName,
                             List<Field> fields) {

    /** One flow field ({@code name} is the flow field's name). */
    public record Field(String name,
                        String dataType,
                        String kind,
                        String piiKind,
                        List<ImportPreviewDto.PreviewRule> rules,
                        List<ImportPreviewDto.Skipped> skipped) {
    }
}
