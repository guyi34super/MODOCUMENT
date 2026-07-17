package com.modocument.application.dto;

import java.util.Map;

/**
 * Create body for an extraction job. The credentials map (e.g. dbUsername /
 * dbPassword / apiToken) is used for this run only — held in process memory,
 * never persisted or logged.
 */
public class CreateExtractionJobRequest {

    private String sourceId;
    private Integer templateId;
    private String dataSpec;
    private boolean incremental;
    private Boolean autoStart;
    private Map<String, String> credentials;

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public Integer getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Integer templateId) {
        this.templateId = templateId;
    }

    public String getDataSpec() {
        return dataSpec;
    }

    public void setDataSpec(String dataSpec) {
        this.dataSpec = dataSpec;
    }

    public boolean isIncremental() {
        return incremental;
    }

    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    public Boolean getAutoStart() {
        return autoStart;
    }

    public void setAutoStart(Boolean autoStart) {
        this.autoStart = autoStart;
    }

    public Map<String, String> getCredentials() {
        return credentials;
    }

    public void setCredentials(Map<String, String> credentials) {
        this.credentials = credentials;
    }
}
