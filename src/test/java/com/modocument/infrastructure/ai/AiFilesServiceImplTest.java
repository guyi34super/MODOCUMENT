package com.modocument.infrastructure.ai;

import com.modocument.application.dto.ChatEditPatchResponse;
import com.modocument.application.dto.TableData;
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

class AiFilesServiceImplTest {

    private final ChatModel chatModel = mock(ChatModel.class);

    private AiFilesServiceImpl serviceWithKey(String apiKey) {
        AiFilesProperties properties = new AiFilesProperties();
        properties.setApiKey(apiKey);
        return new AiFilesServiceImpl(properties, ChatClient.create(chatModel));
    }

    private void modelReturns(String text) {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    @Test
    void sendPromptReturnsModelText() {
        modelReturns("hello");

        assertThat(serviceWithKey("real-key").sendPrompt("hi")).isEqualTo("hello");
    }

    @Test
    void sendForObjectParsesStructuredOutput() {
        modelReturns("{\"columns\":[\"A\"],\"rows\":[{\"A\":\"1\"}]}");

        TableData table = serviceWithKey("real-key").sendForObject(
                "tabularize", new ParameterizedTypeReference<TableData>() {
                });

        assertThat(table.getColumns()).containsExactly("A");
        assertThat(table.getRows()).hasSize(1);
    }

    @Test
    void sendForObjectToleratesLeadingDecimalNumbers() {
        // Models sometimes emit `.5` (invalid JSON) for a numeric-looking cell;
        // strict parsing used to 500. The lenient path coerces it to "0.5".
        modelReturns("{\"rows\":[{\"row\":7,\"values\":{\"Salary Net\":.5}}]}");

        ChatEditPatchResponse patch = serviceWithKey("real-key").sendForObject(
                "fix", new ParameterizedTypeReference<ChatEditPatchResponse>() {
                });

        assertThat(patch.rows()).hasSize(1);
        assertThat(patch.rows().get(0).row()).isEqualTo(7);
        assertThat(patch.rows().get(0).values()).containsEntry("Salary Net", "0.5");
    }

    @Test
    void rejectsBlankPrompt() {
        assertThatThrownBy(() -> serviceWithKey("real-key").sendPrompt("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingOrSentinelApiKey() {
        // The message is read by end users, so it names the environment
        // variable an operator sets — not the internal property key.
        assertThatThrownBy(() -> serviceWithKey("").sendPrompt("hi"))
                .isInstanceOf(AiNotConfiguredException.class)
                .hasMessageContaining("AIFILES_OPENAI_APIKEY");
        assertThatThrownBy(() -> serviceWithKey(AiFilesServiceImpl.UNSET_API_KEY).sendPrompt("hi"))
                .isInstanceOf(AiNotConfiguredException.class);
    }
}
