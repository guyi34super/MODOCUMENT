package com.modocument.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI settings, bound from aifiles.openai.* (same keys the original
 * backend used under AIfiles:OpenAI:*).
 */
@ConfigurationProperties(prefix = "aifiles.openai")
public class AiFilesProperties {

    private String apiKey = "";
    private String modelId = "gpt-4o";
    private String baseUrl = "https://api.openai.com/v1";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
