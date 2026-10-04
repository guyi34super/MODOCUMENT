package com.modocument.infrastructure.ai;

import com.modocument.application.dto.ChatEditPatchResponse;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plumbing behavior of the AI gateway (structured output parsing, the
 * not-configured refusal). The C8/C9/C10 security gates have their own
 * dedicated tests: {@code AiGatewayC9Test}, {@code AiGatewayC8LoggingTest}
 * and {@code AiOutputScannerTest}.
 */
class AiGatewayTest {

    private static final int BANK = 1;

    private final ChatModel chatModel = mock(ChatModel.class);

    private AiGateway serviceWithKey(String apiKey) {
        AiFilesProperties properties = new AiFilesProperties();
        properties.setApiKey(apiKey);
        return new AiGateway(properties, ChatClient.create(chatModel),
                new AiOutputScanner(), mock(AuditRecorder.class));
    }

    private void modelReturns(String text) {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private static <T> T inTenant(java.util.concurrent.Callable<T> action) {
        try {
            return TenantContext.runAs(BANK, action);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void sendPromptReturnsModelText() {
        modelReturns("hello");

        assertThat(inTenant(() -> serviceWithKey("real-key")
                .sendPrompt(PromptEnvelope.of(AiPurpose.DIAGNOSTIC, "hi"))))
                .isEqualTo("hello");
    }

    @Test
    void sendForObjectParsesStructuredOutput() {
        modelReturns("{\"columns\":[\"A\"],\"rows\":[{\"A\":\"1\"}]}");

        TableData table = inTenant(() -> serviceWithKey("real-key").sendForObject(
                PromptEnvelope.of(AiPurpose.TABLE_EXTRACTION, "tabularize"),
                new ParameterizedTypeReference<TableData>() {
                }));

        assertThat(table.getColumns()).containsExactly("A");
        assertThat(table.getRows()).hasSize(1);
    }

    @Test
    void sendForObjectToleratesLeadingDecimalNumbers() {
        // Models sometimes emit `.5` (invalid JSON) for a numeric-looking cell;
        // strict parsing used to 500. The lenient path coerces it to "0.5".
        modelReturns("{\"rows\":[{\"row\":7,\"values\":{\"Salary Net\":.5}}]}");

        ChatEditPatchResponse patch = inTenant(() -> serviceWithKey("real-key").sendForObject(
                PromptEnvelope.of(AiPurpose.CHAT_EDIT, "fix"),
                new ParameterizedTypeReference<ChatEditPatchResponse>() {
                }));

        assertThat(patch.rows()).hasSize(1);
        assertThat(patch.rows().get(0).row()).isEqualTo(7);
        assertThat(patch.rows().get(0).values()).containsEntry("Salary Net", "0.5");
    }

    @Test
    void rejectsBlankPrompt() {
        assertThatThrownBy(() -> inTenant(() -> serviceWithKey("real-key")
                .sendPrompt(PromptEnvelope.of(AiPurpose.DIAGNOSTIC, "  "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingOrSentinelApiKey() {
        // The message is read by end users, so it names the environment
        // variable an operator sets — not the internal property key.
        assertThatThrownBy(() -> inTenant(() -> serviceWithKey("")
                .sendPrompt(PromptEnvelope.of(AiPurpose.DIAGNOSTIC, "hi"))))
                .isInstanceOf(AiNotConfiguredException.class)
                .hasMessageContaining("AIFILES_OPENAI_APIKEY");
        assertThatThrownBy(() -> inTenant(() -> serviceWithKey(AiGateway.UNSET_API_KEY)
                .sendPrompt(PromptEnvelope.of(AiPurpose.DIAGNOSTIC, "hi"))))
                .isInstanceOf(AiNotConfiguredException.class);
    }
}
