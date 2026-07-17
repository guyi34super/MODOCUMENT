package com.modocument.application.dto;

/** Body for bridging an extraction job into the document pipeline. */
public class BridgeRequest {

    private int templateId;
    private Integer maxRows;
    private boolean llmClean;

    public int getTemplateId() {
        return templateId;
    }

    public void setTemplateId(int templateId) {
        this.templateId = templateId;
    }

    public Integer getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(Integer maxRows) {
        this.maxRows = maxRows;
    }

    public boolean isLlmClean() {
        return llmClean;
    }

    public void setLlmClean(boolean llmClean) {
        this.llmClean = llmClean;
    }
}
