package com.modocument.infrastructure.ai;

import com.modocument.application.port.AiFilesService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
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
    public boolean isConfigured() {
        String apiKey = properties.getApiKey();
        return apiKey != null && !apiKey.isBlank() && !UNSET_API_KEY.equals(apiKey);
    }

    @Override
    public String sendPrompt(String prompt) {
        return sendPrompt(prompt, null);
    }

    @Override
    public String sendPrompt(String prompt, String modelId) {
        requireConfigured(prompt);
        ChatClientRequestSpec request = chatClient.prompt();
        if (modelId != null && !modelId.isBlank()) {
            request = request.options(OpenAiChatOptions.builder().model(modelId).build());
        }
        String response = request.user(prompt).call().content();
        return response == null ? "" : response;
    }

    @Override
    public <T> T sendForObject(String prompt, ParameterizedTypeReference<T> type) {
        return sendForObject(prompt, type, null);
    }

    @Override
    public <T> T sendForObject(String prompt, ParameterizedTypeReference<T> type, String modelId) {
        requireConfigured(prompt);
        // Keep Spring AI's JSON-schema/format instructions in the prompt so the
        // model still returns matching JSON, but parse the reply with our
        // lenient/repairing parser instead of the strict converter — LLMs emit
        // off-spec JSON (e.g. `.5`) that would otherwise throw a 500.
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        ChatClientRequestSpec request = chatClient.prompt();
        if (modelId != null && !modelId.isBlank()) {
            request = request.options(OpenAiChatOptions.builder().model(modelId).build());
        }
        String raw = request
                .user(prompt + "\n" + converter.getFormat())
                .call()
                .content();
        if (LlmStructuredJsonParser.looksLikeInstructionEcho(raw)) {
            throw new IllegalStateException(
                    "The AI model repeated its own formatting instructions instead of returning a result. "
                            + "This usually means the selected model is too small or the input exceeded its "
                            + "context window. Nothing was changed — try a smaller selection or a stronger model.");
        }
        try {
            return LlmStructuredJsonParser.parseInto(raw, type.getType());
        } catch (RuntimeException lenientFailed) {
            // Last resort: never worse than the previous strict behavior.
            try {
                return converter.convert(raw == null ? "" : raw);
            } catch (RuntimeException strictFailed) {
                throw strictFailed;
            }
        }
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
