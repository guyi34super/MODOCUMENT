package com.modocument.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** One field of a custom template: name, data type and its validation rules. */
public class CreateTemplateFieldDto {

    /** TemplateFields.FieldName is varchar(255). */
    @NotBlank
    @Size(max = 255)
    private String fieldName;

    /** TemplateDataType name ("Text"/"Number"/"Date") or its numeric id as a string. */
    @NotBlank
    @Size(max = 100)
    private String dataType;

    @Size(max = 100)
    @Valid
    private List<CreateTemplateRuleDto> rules;

    /**
     * Optional explicit PII tag (ws12, PRIV-004): a {@code PiiKind} name
     * ("NIC"/"ACCOUNT_NUMBER"/"EMAIL"/"PHONE"/"NAME"/"NONE", case-insensitive).
     * Null/blank means "classify automatically on first use".
     */
    @Size(max = 20)
    private String piiKind;

    /*
     * ws23 extraction hints for the code extractor (all optional): alternative
     * header/label texts, a label regex, a value regex (group 1 when present)
     * and DateTimeFormatter patterns. The regexes are vetted by SafeRegex.
     */

    @Size(max = 50)
    private List<@Size(max = 255) String> aliases;

    @Size(max = 500)
    private String labelPattern;

    @Size(max = 500)
    private String valuePattern;

    @Size(max = 20)
    private List<@Size(max = 100) String> dateFormats;

    public CreateTemplateFieldDto() {
    }

    public CreateTemplateFieldDto(String fieldName, String dataType, List<CreateTemplateRuleDto> rules) {
        this.fieldName = fieldName;
        this.dataType = dataType;
        this.rules = rules;
    }

    public CreateTemplateFieldDto(String fieldName, String dataType,
                                  List<CreateTemplateRuleDto> rules, String piiKind) {
        this(fieldName, dataType, rules);
        this.piiKind = piiKind;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public List<CreateTemplateRuleDto> getRules() {
        return rules;
    }

    public void setRules(List<CreateTemplateRuleDto> rules) {
        this.rules = rules;
    }

    public String getPiiKind() {
        return piiKind;
    }

    public void setPiiKind(String piiKind) {
        this.piiKind = piiKind;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases;
    }

    public String getLabelPattern() {
        return labelPattern;
    }

    public void setLabelPattern(String labelPattern) {
        this.labelPattern = labelPattern;
    }

    public String getValuePattern() {
        return valuePattern;
    }

    public void setValuePattern(String valuePattern) {
        this.valuePattern = valuePattern;
    }

    public List<String> getDateFormats() {
        return dateFormats;
    }

    public void setDateFormats(List<String> dateFormats) {
        this.dateFormats = dateFormats;
    }
}
