package com.modocument.application.dto;

/** Result of the transformation: the JSON string extracted by the LLM. */
public class TransformResult {

    private String jsonPayload = "";

    public TransformResult() {
    }

    public TransformResult(String jsonPayload) {
        this.jsonPayload = jsonPayload;
    }

    public String getJsonPayload() {
        return jsonPayload;
    }

    public void setJsonPayload(String jsonPayload) {
        this.jsonPayload = jsonPayload;
    }
}
