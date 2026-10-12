package com.modocument.application.usecase;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** LLM settings for agentic data-extraction features (schema inference, summaries, cleaning). */
@ConfigurationProperties(prefix = "modocument.extraction-llm")
public class ExtractionLlmProperties {

    /**
     * Model for extraction agents (summarize / infer-schema / clean-records);
     * blank (the default since ws28) = the global {@code aifiles.openai.model-id}.
     * Set it to a pinned tag to use a different model for these agents.
     */
    private String modelId = "";

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }
}
