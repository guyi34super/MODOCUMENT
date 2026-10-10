package com.modocument.infrastructure.ai;

import com.modocument.application.dto.TransformRequest;
import com.modocument.application.mapping.MappingContext;
import com.modocument.application.mapping.MappingProperties;
import com.modocument.application.mapping.ValueShapeScorer;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.TenantContext;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.application.usecase.AutoMapUseCase;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.file.StorageProperties;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.prompt.AutoMappingPrompt;
import com.modocument.infrastructure.prompt.ExtractionPrompt;
import com.modocument.infrastructure.prompt.PromptFactory;
import com.modocument.infrastructure.rule.RegexRule;
import com.modocument.infrastructure.transform.TransformServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gate C9 — identifiers are pseudonymised before every prompt, and no prompt
 * can mix two banks' data:
 *
 * <ul>
 *   <li>an upload with real-format NIC/account values driven through the
 *       transform and automap paths produces a model request that contains
 *       ONLY tokens;</li>
 *   <li>an envelope carrying a part sourced from another bank is refused and
 *       audited (AI_PROMPT_REFUSED, DENIED);</li>
 *   <li>a call with no bound tenant context is refused and audited;</li>
 *   <li>a call site that skipped tokenization is caught by the gateway's own
 *       leak scan (AI_PROMPT_BLOCKED_PII) and never reaches the model.</li>
 * </ul>
 */
class AiGatewayC9Test {

    private static final int BANK = 1;
    /** Real-format Mauritius NIC and a bare account-number run. */
    private static final String NIC = "M090976290148E";
    private static final String ACCOUNT = "123456789012";

    @TempDir
    Path tempDir;

    private final ChatModel chatModel = mock(ChatModel.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PiiTokenizer tokenizer = new PiiTokenizer(new PrivacyProperties());
    private final AiGateway gateway = newGateway();

    private AiGateway newGateway() {
        AiFilesProperties properties = new AiFilesProperties();
        properties.setApiKey("real-key");
        return new AiGateway(properties, ChatClient.create(chatModel),
                new AiOutputScanner(), audit);
    }

    private void modelReturns(String text) {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private String capturedModelRequest() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, atLeastOnce()).call(captor.capture());
        return captor.getValue().getContents();
    }

    private static <T> T inBank(java.util.concurrent.Callable<T> action) {
        try {
            return TenantContext.runAs(BANK, action);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void transformUploadWithRealIdentifiersSendsOnlyTokens() throws IOException {
        Files.createDirectories(tempDir.resolve("uploads"));
        Files.writeString(tempDir.resolve("uploads").resolve("statement.txt"), "x");
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.extractText(any())).thenReturn(
                "Customer NIC " + NIC + " paid from account " + ACCOUNT
                        + " contact ada@example.com");
        TransformServiceImpl transform = new TransformServiceImpl(
                new PromptFactory(List.of(new ExtractionPrompt())), gateway, parser,
                new UploadStorage(new StorageProperties(tempDir.toString())), tokenizer);
        // The model works on tokens and returns them; the service detokenizes.
        modelReturns("```json\n{\"nic\":\"[ID_1]\"}\n```");

        TransformRequest request = new TransformRequest();
        request.setFileName("statement.txt");
        String extracted = inBank(() -> transform.transform(request, "[]").getJsonPayload());

        String sent = capturedModelRequest();
        assertThat(sent).doesNotContain(NIC).doesNotContain(ACCOUNT)
                .doesNotContain("ada@example.com");
        assertThat(sent).contains("[ID_1]").contains("[ID_2]").contains("[EMAIL_1]");
        // Reversible: the caller's reply carries the real value again.
        assertThat(extracted).contains(NIC);
    }

    @Test
    void autoMapSampleValuesAreTokenizedBeforeThePrompt() {
        AutoMapUseCase autoMap = new AutoMapUseCase(
                List.of(new AutoMappingPrompt()), gateway,
                new ValueShapeScorer(mock(ValidationRuleRepository.class), List.of(new RegexRule())),
                mock(IngestionJobRepository.class), new MappingProperties(), tokenizer);
        TemplateFields field = new TemplateFields();
        field.setFieldName("CustomerId");
        modelReturns("{}");

        inBank(() -> autoMap.map(new MappingContext(
                List.of("Zebra_Quotient"), List.of(field),
                List.of(Map.of("Zebra_Quotient", NIC),
                        Map.of("Zebra_Quotient", ACCOUNT)),
                1, BANK)));

        String sent = capturedModelRequest();
        assertThat(sent).doesNotContain(NIC).doesNotContain(ACCOUNT);
        assertThat(sent).contains("[ID_");
    }

    @Test
    void promptMixingTwoBanksIsRefusedAndAudited() {
        assertThatThrownBy(() -> inBank(() -> gateway.sendPrompt(
                PromptEnvelope.builder(AiPurpose.CHAT_EDIT)
                        .bank(BANK)
                        .part("rows from this bank", BANK)
                        .part("rows from another bank", 2)
                        .build())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mix");

        AuditEventSpec refused = capturedAudit("AI_PROMPT_REFUSED");
        assertThat(refused.outcome()).isEqualTo(AuditOutcome.DENIED);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void envelopeBankDifferentFromBoundTenantIsRefused() {
        assertThatThrownBy(() -> inBank(() -> gateway.sendPrompt(
                PromptEnvelope.builder(AiPurpose.CHAT_EDIT)
                        .bank(2) // entity stamped bank 2, caller bound to bank 1
                        .part("content")
                        .build())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(capturedAudit("AI_PROMPT_REFUSED").outcome()).isEqualTo(AuditOutcome.DENIED);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void missingTenantContextIsRefusedAndAudited() {
        PromptEnvelope envelope = inBank(
                () -> PromptEnvelope.of(AiPurpose.DIAGNOSTIC, "hello"));

        // No TenantContext bound on the calling thread.
        assertThatThrownBy(() -> gateway.sendPrompt(envelope))
                .isInstanceOf(IllegalStateException.class);

        assertThat(capturedAudit("AI_PROMPT_REFUSED").outcome()).isEqualTo(AuditOutcome.DENIED);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void rawIdentifierShapesInTheAssembledPromptAreBlockedBeforeTheModel() {
        // A hypothetical call site that forgot to tokenize: the gateway's own
        // leak scan is the last line of defense.
        assertThatThrownBy(() -> inBank(() -> gateway.sendPrompt(
                PromptEnvelope.of(AiPurpose.DIAGNOSTIC,
                        "customer " + NIC + " account " + ACCOUNT))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tokenize");

        AuditEventSpec blocked = capturedAudit("AI_PROMPT_BLOCKED_PII");
        assertThat(blocked.outcome()).isEqualTo(AuditOutcome.DENIED);
        // The audit evidence names the shape categories, never the values.
        assertThat(String.valueOf(blocked.newValues())).doesNotContain(NIC).doesNotContain(ACCOUNT);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    private AuditEventSpec capturedAudit(String action) {
        ArgumentCaptor<AuditEventSpec> captor = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit, atLeastOnce()).record(captor.capture());
        return captor.getAllValues().stream()
                .filter(spec -> action.equals(spec.action()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + action + " audit event recorded"));
    }
}
