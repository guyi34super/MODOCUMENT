package com.modocument.application.dto;

public class DataCleaningPromptInput extends PromptInput {

    private String tableJson;
    private String dataSpec;

    public DataCleaningPromptInput() {
    }

    public DataCleaningPromptInput(String tableJson, String dataSpec) {
        this.tableJson = tableJson;
        this.dataSpec = dataSpec;
    }

    public String getTableJson() {
        return tableJson;
    }

    public void setTableJson(String tableJson) {
        this.tableJson = tableJson;
    }

    public String getDataSpec() {
        return dataSpec;
    }

    public void setDataSpec(String dataSpec) {
        this.dataSpec = dataSpec;
    }
}
