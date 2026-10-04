package com.modocument.infrastructure.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gate C8 — prompt and completion CONTENT never reaches a log line (at any
 * level, including DEBUG) and the AI_CALL audit evidence is content-free:
 * purposes, models and counts only.
 */
class AiGatewayC8LoggingTest {

    private static final int BANK = 1;
    private static final String PROMPT_MARKER = "UNIQUE-PROMPT-CONTENT-7f3a";
    private static final String COMPLETION_MARKER = "UNIQUE-COMPLETION-CONTENT-9b1c";

    private final ChatModel chatModel = mock(ChatModel.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final AiGateway gateway = newGateway();

    private ListAppender<ILoggingEvent> logLines;
    private Logger root;
    private Level previousLevel;

    private AiGateway newGateway() {
        AiFilesProperties properties = new AiFilesProperties();
        properties.setApiKey("real-key");
        return new AiGateway(properties, ChatClient.create(chatModel),
                new AiOutputScanner(), audit);
    }

    @BeforeEach
    void captureAllLogging() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        previousLevel = root.getLevel();
        root.setLevel(Level.TRACE); // nothing may leak even at the most verbose level
        logLines = new ListAppender<>();
        logLines.start();
        root.addAppender(logLines);
    }

    @AfterEach
    void detach() {
        root.detachAppender(logLines);
        root.setLevel(previousLevel);
    }

    private String allLoggedText() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : logLines.list) {
            all.append(event.getFormattedMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void successfulCallLogsNoPromptOrCompletionContent() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(COMPLETION_MARKER)))));

        String reply = TenantContext.runAs(BANK, () -> gateway.sendPrompt(
                PromptEnvelope.of(AiPurpose.DIAGNOSTIC, "summarize " + PROMPT_MARKER)));

        assertThat(reply).contains(COMPLETION_MARKER);
        assertThat(allLoggedText())
                .doesNotContain(PROMPT_MARKER)
                .doesNotContain(COMPLETION_MARKER);
    }

    @Test
    void aiCallAuditEvidenceIsContentFree() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(COMPLETION_MARKER)))));

        String ignored = TenantContext.runAs(BANK, () -> gateway.sendPrompt(
                PromptEnvelope.of(AiPurpose.DIAGNOSTIC, "summarize " + PROMPT_MARKER)));

        ArgumentCaptor<AuditEventSpec> captor = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit, atLeastOnce()).record(captor.capture());
        AuditEventSpec call = captor.getAllValues().stream()
                .filter(spec -> "AI_CALL".equals(spec.action()))
                .findFirst().orElseThrow();
        assertThat(call.newValues()).isInstanceOf(Map.class);
        Map<?, ?> evidence = (Map<?, ?>) call.newValues();
        // Counts and names only — defined allowlist, no content fields.
        List<String> evidenceKeys = evidence.keySet().stream().map(String::valueOf).toList();
        assertThat(evidenceKeys).isSubsetOf(
                "purpose", "model", "promptChars", "completionChars",
                "promptTokens", "completionTokens");
        assertThat(String.valueOf(evidence))
                .doesNotContain(PROMPT_MARKER)
                .doesNotContain(COMPLETION_MARKER);
    }

    @Test
    void refusalPathsLogShapeCategoriesNeverValues() {
        String nic = "M090976290148E";
        assertThatThrownBy(() -> TenantContext.runAs(BANK, () -> {
            gateway.sendPrompt(PromptEnvelope.of(AiPurpose.DIAGNOSTIC, "customer " + nic));
        })).isInstanceOf(IllegalStateException.class);

        assertThat(allLoggedText()).doesNotContain(nic);
    }
}
