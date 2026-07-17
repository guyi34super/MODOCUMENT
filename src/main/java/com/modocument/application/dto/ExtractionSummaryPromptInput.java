package com.modocument.application.dto;

public class ExtractionSummaryPromptInput extends PromptInput {

    private String statsJson;
    private String sampleRecordsJson;
    private String dataSpec;

    public ExtractionSummaryPromptInput() {
    }

    public ExtractionSummaryPromptInput(String statsJson, String sampleRecordsJson, String dataSpec) {
        this.statsJson = statsJson;
        this.sampleRecordsJson = sampleRecordsJson;
        this.dataSpec = dataSpec;
    }

    public String getStatsJson() {
        return statsJson;
    }

    public void setStatsJson(String statsJson) {
        this.statsJson = statsJson;
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
