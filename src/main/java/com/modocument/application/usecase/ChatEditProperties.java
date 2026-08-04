package com.modocument.application.usecase;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Guardrail settings for AI chat table editing. */
@ConfigurationProperties(prefix = "modocument.chat-edit")
public class ChatEditProperties {

    /**
     * When a full-table edit (no validation errors) changes a larger fraction
     * of cells than this, and the instruction is not a bulk request, the edit
     * is rejected as a probable wholesale rewrite. 0.6 = 60% of cells.
     */
    private double fullTableMaxChangeRatio = 0.6;

    /** LLM used for validation-step chat edits (overrides the global default). */
    private String modelId = "deepseek-r1:8b";

    public double getFullTableMaxChangeRatio() {
        return fullTableMaxChangeRatio;
    }

    public void setFullTableMaxChangeRatio(double fullTableMaxChangeRatio) {
        this.fullTableMaxChangeRatio = fullTableMaxChangeRatio;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }
}
