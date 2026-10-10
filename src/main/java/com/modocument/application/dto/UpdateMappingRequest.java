package com.modocument.application.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Body of PUT /api/ingestion/jobs/{id}/mapping: source column -> template field (or null). */
public class UpdateMappingRequest {

    @NotEmpty
    @Size(max = 500)
    private Map<String, String> mapping;

    public UpdateMappingRequest() {
    }

    public UpdateMappingRequest(Map<String, String> mapping) {
        this.mapping = mapping;
    }

    public Map<String, String> getMapping() {
        return mapping;
    }

    public void setMapping(Map<String, String> mapping) {
        this.mapping = mapping;
    }
}
