package com.modocument.application.dto;

import java.util.List;

/** Request body of POST /Templates/CreateWithFields. */
public class CreateTemplateRequest {

    private String name;
    private String description;
    private List<CreateTemplateFieldDto> fields;

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
}
