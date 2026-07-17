package com.modocument.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal OpenAI chat-completions client; only single-prompt completion is needed. */
@Component
public class OpenAiChatClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    public OpenAiChatClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public String complete(String baseUrl, String apiKey, String modelId, String prompt) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", modelId);
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call OpenAI: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling OpenAI.", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "OpenAI returned HTTP " + response.statusCode() + ": " + response.body());
        }

        try {
            JsonNode content = MAPPER.readTree(response.body())
                    .path("choices").path(0).path("message").path("content");
            return content.isMissingNode() || content.isNull() ? "" : content.asText();
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse OpenAI response.", e);
        }
    }
}
