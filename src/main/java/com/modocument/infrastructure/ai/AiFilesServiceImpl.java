package com.modocument.infrastructure.ai;

import com.modocument.application.port.AiFilesService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

@Service
public class AiFilesServiceImpl implements AiFilesService {

    /**
     * Sentinel bridged into spring.ai.openai.api-key when no real key is
     * configured, so the OpenAI auto-configuration can boot keyless.
     */
    public static final String UNSET_API_KEY = "unset";

    private final AiFilesProperties properties;
    private final ChatClient chatClient;

    public AiFilesServiceImpl(AiFilesProperties properties, ChatClient chatClient) {
        this.properties = properties;
        this.chatClient = chatClient;
    }

    @Override
    public String sendPrompt(String prompt) {
        requireConfigured(prompt);
        String response = chatClient.prompt().user(prompt).call().content();
        return response == null ? "" : response;
    }

    @Override
    public <T> T sendForObject(String prompt, ParameterizedTypeReference<T> type) {
        requireConfigured(prompt);
        return chatClient.prompt().user(prompt).call().entity(type);
    }

    private void requireConfigured(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt cannot be empty.");
        }
        // Validated per call (not at startup) so the app can boot without a key
        // and only the LLM endpoints fail when it is missing.
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank() || UNSET_API_KEY.equals(apiKey)) {
            throw new AiNotConfiguredException(
                    "The AI assistant is not configured on this server. "
                            + "Set AIFILES_OPENAI_APIKEY and restart, or continue without AI "
                            + "features (mapping, validation and export all work without them).");
        }
    }
}
