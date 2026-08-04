package com.modocument.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the Spring AI {@link ChatClient} built from the auto-configured
 * OpenAI chat model. Connection settings are bridged from the legacy
 * aifiles.openai.* keys in application.yml, so AIFILES_OPENAI_APIKEY /
 * AIFILES_OPENAI_MODELID keep working unchanged.
 */
@Configuration
public class SpringAiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("Always reply in English only. Do not use any other language.")
                .build();
    }
}
