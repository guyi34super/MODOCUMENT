package com.modocument.application.usecase;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** LLM settings for agentic data-extraction features (schema inference, summaries, cleaning). */
@ConfigurationProperties(prefix = "modocument.extraction-llm")
public class ExtractionLlmProperties {

    /**
     * Model for extraction agents. Defaults to a local Ollama tag
     * ({@code mistral:latest}); overrides the global {@code aifiles.openai.model-id}
     * for summarize / infer-schema / clean-records only.
     */
    private String modelId = "mistral:latest";

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }
}
