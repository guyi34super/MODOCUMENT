package com.modocument.infrastructure.file;

import com.modocument.infrastructure.ai.AiFilesProperties;
import com.modocument.infrastructure.ai.AiFilesServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisionOcrServiceTest {

    private final ChatModel chatModel = mock(ChatModel.class);

    private VisionOcrService service(String apiKey, boolean enabled) {
        VisionOcrProperties properties = new VisionOcrProperties();
        properties.setEnabled(enabled);
        AiFilesProperties aiProperties = new AiFilesProperties();
        aiProperties.setApiKey(apiKey);
        return new VisionOcrService(ChatClient.create(chatModel), properties, aiProperties);
    }

    private void modelReturns(String text) {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    @Test
    void returnsModelTextWhenEnabledWithRealKey() {
        modelReturns("INVOICE #42");

        assertThat(service("real-key", true).extractText(image(100, 60)))
                .isEqualTo("INVOICE #42");
    }

    @Test
    void disabledWithoutRealKey() {
        assertThat(service(AiFilesServiceImpl.UNSET_API_KEY, true).extractText(image(10, 10)))
                .isEmpty();
        assertThat(service("", true).extractText(image(10, 10))).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void disabledByProperty() {
        VisionOcrService service = service("real-key", false);

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.extractText(image(10, 10))).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void returnsEmptyWhenModelFails() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("boom"));

        assertThat(service("real-key", true).extractText(image(10, 10))).isEmpty();
    }

    @Test
    void sendsSinglePngMediaWithOcrPrompt() {
        modelReturns("text");

        service("real-key", true).extractText(image(80, 40));

        UserMessage userMessage = capturedUserMessage();
        assertThat(userMessage.getText()).isEqualTo(VisionOcrService.OCR_PROMPT);
        assertThat(userMessage.getMedia()).hasSize(1);
        assertThat(userMessage.getMedia().get(0).getMimeType().toString()).isEqualTo("image/png");
    }

    @Test
    void downscalesOversizedImagesBeforeUpload() throws IOException {
        modelReturns("text");

        service("real-key", true).extractText(image(5000, 2500));

        Media media = capturedUserMessage().getMedia().get(0);
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(media.getDataAsByteArray()));
        assertThat(Math.max(sent.getWidth(), sent.getHeight())).isLessThanOrEqualTo(2048);
        assertThat(sent.getWidth()).isGreaterThan(sent.getHeight());
    }

    private UserMessage capturedUserMessage() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        return captor.getValue().getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
