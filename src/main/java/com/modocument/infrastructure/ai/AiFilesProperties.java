package com.modocument.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI settings, bound from aifiles.openai.* (same keys the original
 * backend used under AIfiles:OpenAI:*).
 */
@ConfigurationProperties(prefix = "aifiles.openai")
public class AiFilesProperties {

    private String apiKey = "";
    private String modelId = "moonshotai/kimi-k3";
    /**
     * Host root, without the /v1 suffix the pre-Spring-AI client used —
     * Spring AI appends the /v1/chat/completions path itself.
     */
    private String baseUrl = "https://api.openai.com";

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
