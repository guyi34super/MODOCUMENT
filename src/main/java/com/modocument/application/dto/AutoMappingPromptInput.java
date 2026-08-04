package com.modocument.application.dto;

import java.util.List;
import java.util.Map;

/**
 * Structured input for the auto-mapping LLM prompt: source columns (with sample
 * values), template fields (with data types and rule hints), and mappings
 * already decided by code tiers.
 */
public class AutoMappingPromptInput extends PromptInput {

    private List<AutoMappingPromptInput.ColumnInfo> columnsToMap;
    private List<FieldInfo> templateFields;
    private Map<String, String> confirmedMappings;
    private Map<String, String> extractionSuggestedMappings;
    private int sampleSize = 5;

    public AutoMappingPromptInput() {
    }

    public AutoMappingPromptInput(List<ColumnInfo> columnsToMap,
                                  List<FieldInfo> templateFields,
                                  Map<String, String> confirmedMappings,
                                  int sampleSize) {
        this(columnsToMap, templateFields, confirmedMappings, Map.of(), sampleSize);
    }

    public AutoMappingPromptInput(List<ColumnInfo> columnsToMap,
                                  List<FieldInfo> templateFields,
                                  Map<String, String> confirmedMappings,
                                  Map<String, String> extractionSuggestedMappings,
                                  int sampleSize) {
        this.columnsToMap = columnsToMap;
        this.templateFields = templateFields;
        this.confirmedMappings = confirmedMappings;
        this.extractionSuggestedMappings = extractionSuggestedMappings;
        this.sampleSize = sampleSize;
    }

    public record ColumnInfo(String name, List<String> sampleValues) {
    }

    public record FieldInfo(String name, String dataType, boolean available, String ruleHint) {
    }

    public List<ColumnInfo> getColumnsToMap() {
        return columnsToMap;
    }

    public void setColumnsToMap(List<ColumnInfo> columnsToMap) {
        this.columnsToMap = columnsToMap;
    }

    public List<FieldInfo> getTemplateFields() {
        return templateFields;
    }

    public void setTemplateFields(List<FieldInfo> templateFields) {
        this.templateFields = templateFields;
    }

    public Map<String, String> getConfirmedMappings() {
        return confirmedMappings;
    }

    public void setConfirmedMappings(Map<String, String> confirmedMappings) {
        this.confirmedMappings = confirmedMappings;
    }

    public Map<String, String> getExtractionSuggestedMappings() {
        return extractionSuggestedMappings;
    }

    public void setExtractionSuggestedMappings(Map<String, String> extractionSuggestedMappings) {
        this.extractionSuggestedMappings = extractionSuggestedMappings;
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public void setSampleSize(int sampleSize) {
        this.sampleSize = sampleSize;
    }
}
