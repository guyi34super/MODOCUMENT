package com.modocument.application.dto;

public class SchemaInferencePromptInput extends PromptInput {

    private String sampleRecordsJson;
    private String dataSpec;

    public SchemaInferencePromptInput() {
    }

    public SchemaInferencePromptInput(String sampleRecordsJson, String dataSpec) {
        this.sampleRecordsJson = sampleRecordsJson;
        this.dataSpec = dataSpec;
    }

    public String getSampleRecordsJson() {
        return sampleRecordsJson;
    }

    public void setSampleRecordsJson(String sampleRecordsJson) {
        this.sampleRecordsJson = sampleRecordsJson;
    }

    public String getDataSpec() {
        return dataSpec;
    }

    public void setDataSpec(String dataSpec) {
        this.dataSpec = dataSpec;
    }
}
