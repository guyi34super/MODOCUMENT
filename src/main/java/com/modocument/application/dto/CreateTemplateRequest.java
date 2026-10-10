package com.modocument.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request body of POST /Templates/CreateWithFields. */
public class CreateTemplateRequest {

    /** Template.Name is varchar(255). */
    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String description;

    @NotEmpty
    @Size(max = 200)
    @Valid
    private List<CreateTemplateFieldDto> fields;

    /**
     * ws22: {@code PRIVATE} (default when absent) or {@code BANK} — sharing
     * with the bank at creation requires template:publish (403 otherwise).
     */
    @Size(max = 10)
    private String visibility;

    public CreateTemplateRequest() {
    }

    public CreateTemplateRequest(String name, String description, List<CreateTemplateFieldDto> fields) {
        this.name = name;
        this.description = description;
        this.fields = fields;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<CreateTemplateFieldDto> getFields() {
        return fields;
    }

    public void setFields(List<CreateTemplateFieldDto> fields) {
        this.fields = fields;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }
}
