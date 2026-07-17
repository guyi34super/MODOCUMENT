package com.modocument.infrastructure.ai;

import com.modocument.application.port.AiFilesService;
import org.springframework.stereotype.Service;

@Service
public class AiFilesServiceImpl implements AiFilesService {

    private final AiFilesProperties properties;
    private final OpenAiChatClient chatClient;

    public AiFilesServiceImpl(AiFilesProperties properties, OpenAiChatClient chatClient) {
        this.properties = properties;
        this.chatClient = chatClient;
    }

    @Override
    public String sendPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt cannot be empty.");
        }
        // Validated per call (not at startup) so the app can boot without a key
        // and only the LLM endpoints fail when it is missing.
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("aifiles.openai.api-key must be configured.");
        }

        String response = chatClient.complete(
                properties.getBaseUrl(), properties.getApiKey(), properties.getModelId(), prompt);
        return response == null ? "" : response;
    }
}
