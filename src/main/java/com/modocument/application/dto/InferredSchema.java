package com.modocument.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** LLM structured output: a suggested template schema for extracted records. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InferredSchema(List<InferredField> fields) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InferredField(String name, String dataType, String example) {
    }
}
