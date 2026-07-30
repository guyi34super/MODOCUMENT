package com.modocument.api.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiExceptionHandlerTest {

    @Test
    void mapsOpenRouter402ToFriendlyCreditsMessage() {
        String raw = "HTTP 402 - {\"error\":{\"message\":\"This request requires more credits\"}}";
        assertThat(AiExceptionHandler.friendlyMessage(raw))
                .contains("credits are insufficient");
    }

    @Test
    void extractsJsonMessageWhenPresent() {
        String raw = "HTTP 500 - {\"error\":{\"message\":\"Model overloaded\"}}";
        assertThat(AiExceptionHandler.friendlyMessage(raw)).isEqualTo("Model overloaded");
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
