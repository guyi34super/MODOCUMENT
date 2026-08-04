package com.modocument.api.controller;

import com.modocument.infrastructure.ai.AiErrorMessages;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiExceptionHandlerTest {

    @Test
    void mapsHuggingFace402ToBillingGuidance() {
        String raw = "HTTP 402 - {\"error\":\"You have depleted your monthly included credits. "
                + "Purchase pre-paid credits to continue using Inference Providers.\"}";
        assertThat(AiErrorMessages.friendlyMessage(raw))
                .contains("Hugging Face")
                .contains("huggingface.co/settings/billing")
                .doesNotContain("OpenRouter");
    }

    @Test
    void mapsOpenRouter402ToFriendlyCreditsMessage() {
        String raw = "HTTP 402 - {\"error\":{\"message\":\"This request requires more credits\"}}";
        assertThat(AiErrorMessages.friendlyMessage(raw))
                .contains("credits are insufficient")
                .doesNotContain("OpenRouter");
    }

    @Test
    void mapsOpenRouter401UserNotFoundToApiKeyGuidance() {
        String raw = "HTTP 401 - {\"error\":{\"message\":\"User not found.\",\"code\":401}}";
        assertThat(AiErrorMessages.friendlyMessage(raw))
                .contains("API key")
                .contains("AIFILES_OPENAI_BASEURL")
                .doesNotContain("User not found")
                .doesNotContain("OpenRouter");
    }

    @Test
    void extractsJsonMessageWhenPresent() {
        String raw = "HTTP 500 - {\"error\":{\"message\":\"Model overloaded\"}}";
        assertThat(AiErrorMessages.friendlyMessage(raw)).isEqualTo("Model overloaded");
    }

    @Test
    void missingKeyIsServiceUnavailableWithOperatorGuidance() {
        var response = new AiExceptionHandler().handleNotConfigured(
                new com.modocument.infrastructure.ai.AiNotConfiguredException(
                        "The AI assistant is not configured on this server."));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().get("error")).contains("not configured");
    }

    @Test
    void unreachableProviderIsABadGateway() {
        var response = new AiExceptionHandler().handleUnreachable(
                new org.springframework.web.client.ResourceAccessException(
                        "I/O error", new java.net.ConnectException("Connection refused")));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().get("error")).contains("Connection refused");
    }
}
