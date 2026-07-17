package com.modocument.application.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ValidationRequest {

    private int templateId;
    private List<Map<String, String>> rows = new ArrayList<>();

    public ValidationRequest() {
    }

    public ValidationRequest(int templateId, List<Map<String, String>> rows) {
        this.templateId = templateId;
        this.rows = rows;
    }

    public int getTemplateId() {
        return templateId;
    }

    public void setTemplateId(int templateId) {
        this.templateId = templateId;
    }

    public List<Map<String, String>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, String>> rows) {
        this.rows = rows;
    }
}
