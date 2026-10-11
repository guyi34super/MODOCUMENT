package com.modocument.infrastructure.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.TransformRequest;
import com.modocument.application.dto.TransformResult;
import com.modocument.application.mapping.MappingProperties;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.ai.AiCircuitBreaker;
import com.modocument.infrastructure.ai.AiPreflight;
import com.modocument.infrastructure.file.StorageProperties;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.prompt.PromptFactory;
import com.modocument.infrastructure.transform.code.ExtractionCorpus;
import com.modocument.testsupport.FixedUser;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.retry.TransientAiException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The transform path resolves its file through UploadStorage.resolve, which
 * confines every name to the uploads folder — a "../" name must die with a
 * clean 400-shaped IllegalArgumentException before any byte is read or any
 * LLM call is made (the old raw uploadsDir.resolve(fileName) happily walked
 * out of the folder).
 */
class TransformServiceImplTest {

    @TempDir
    Path tempDir;

    private static TransformRequest request(String fileName) {
        TransformRequest request = new TransformRequest();
        request.setFileName(fileName);
        return request;
    }

    @Test
    void traversalFileNamesAreRejectedBeforeAnyReadOrLlmCall() throws Exception {
        // A juicy target outside uploads/ that the old code would have read.
        Files.writeString(tempDir.resolve("secrets.yml"), "jwt-secret: oops");
        Files.createDirectories(tempDir.resolve("uploads"));

        PromptFactory prompts = mock(PromptFactory.class);
        AiFilesService ai = mock(AiFilesService.class);
        DocumentParser parser = mock(DocumentParser.class);
        TransformServiceImpl service = new TransformServiceImpl(prompts, ai, parser,
                new UploadStorage(new StorageProperties(tempDir.toString())),
                new PiiTokenizer(new PrivacyProperties()));

        for (String name : new String[] {"../secrets.yml", "a/../../secrets.yml",
                tempDir.resolve("secrets.yml").toAbsolutePath().toString()}) {
            assertThatThrownBy(() -> service.transform(request(name), "[]"))
                    .as("transform(%s)", name)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.getTemplateId(request(name)))
                    .as("getTemplateId(%s)", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(ai, parser, prompts);
    }

    @Test
    void missingUploadIsNotFoundNotAnError() {
        TransformServiceImpl service = new TransformServiceImpl(
                mock(PromptFactory.class), mock(AiFilesService.class), mock(DocumentParser.class),
                new UploadStorage(new StorageProperties(tempDir.toString())),
                new PiiTokenizer(new PrivacyProperties()));

        assertThatThrownBy(() -> service.transform(request("nope.csv"), "[]"))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ---- ws23 extraction modes -----------------------------------------------------

    private final AiFilesService ai = mock(AiFilesService.class);
    private final PromptFactory prompts = mock(PromptFactory.class);
    private final SettingsReader settings = mock(SettingsReader.class);
    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final TemplateFieldsRepository templateFields = mock(TemplateFieldsRepository.class);
    private final LlmRateLimiter quota = mock(LlmRateLimiter.class);

    private static final String FORM = "Customer form\nName: Ada Lovelace\nCity: London\n";
    private static final String MODEL = "[\"Name\",\"City\"]";

    private TransformServiceImpl withModes(String templateMode) throws Exception {
        Files.createDirectories(tempDir.resolve("uploads"));
        Files.writeString(tempDir.resolve("uploads").resolve("form.txt"), FORM);
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.extractText(any())).thenReturn(FORM);
        Template template = new Template(4, "Form", null);
        template.setExtractionMode(templateMode);
        when(templates.getById(4)).thenReturn(Optional.of(template));
        when(templateFields.listByTemplateId(4)).thenReturn(List.of(field("Name"), field("City")));
        when(settings.getEnumForPlatform(SettingKeys.EXTRACTION_DEFAULT_MODE))
                .thenReturn("AI_WITH_CODE_FALLBACK");
        when(settings.getDecimalForPlatform(SettingKeys.EXTRACTION_CODE_MIN_CONFIDENCE))
                .thenReturn(new BigDecimal("0.75"));
        when(settings.getBoolForPlatform(SettingKeys.AI_ENABLED)).thenReturn(true);
        when(ai.isConfigured()).thenReturn(true);
        when(quota.tryCheck(anyInt())).thenReturn(true);
        when(quota.tryCheck(anyInt(), anyInt())).thenReturn(true);
        PromptStrategy strategy = mock(PromptStrategy.class);
        when(strategy.buildPrompt(any())).thenReturn("prompt");
        when(prompts.getPrompt(any())).thenReturn(strategy);
        return new TransformServiceImpl(prompts, ai, parser,
                new UploadStorage(new StorageProperties(tempDir.toString())),
                new PiiTokenizer(new PrivacyProperties()),
                new CodeParseSupport(ExtractionCorpus.extractor(), new MappingProperties(), null),
                new ExtractionModeResolver(settings),
                new AiPreflight(settings, ai, AiCircuitBreaker.disabled(), quota),
                templates, templateFields, FixedUser.provider(9));
    }

    private static TemplateFields field(String name) {
        TemplateFields field = new TemplateFields();
        field.setFieldName(name);
        field.setTemplateDataTypeId(1);
        return field;
    }

    private static List<Map<String, String>> records(TransformResult result) throws Exception {
        return new ObjectMapper().readValue(result.getJsonPayload(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
    }

    @Test
    void codeOnlyTransformsLabelValueTextWithoutTheAi() throws Exception {
        TransformServiceImpl service = withModes("CODE_ONLY");

        TransformResult result = service.transform(request("form.txt"), MODEL, 4);

        assertThat(records(result)).containsExactly(Map.of("Name", "Ada Lovelace", "City", "London"));
        assertThat(result.getParsedBy()).isEqualTo("CODE");
        assertThat(result.getParseStrategy()).isEqualTo("KEY_VALUE");
        assertThat(result.getParseFallbackReason()).isEqualTo("MODE_CODE_ONLY");
        verifyNoInteractions(ai, prompts, quota);
    }

    @Test
    void aFailingAiFallsBackToCode() throws Exception {
        TransformServiceImpl service = withModes(null);
        when(ai.sendPrompt(any(PromptEnvelope.class))).thenThrow(new TransientAiException("HTTP 500"));

        TransformResult result = service.transform(request("form.txt"), MODEL, 4);

        assertThat(records(result)).containsExactly(Map.of("Name", "Ada Lovelace", "City", "London"));
        assertThat(result.getParseFallbackReason()).isEqualTo("ERROR");
    }

    @Test
    void anAiReplyWithoutJsonIsInvalidOutputAndFallsBack() throws Exception {
        TransformServiceImpl service = withModes(null);
        when(ai.sendPrompt(any(PromptEnvelope.class))).thenReturn("Sorry, I cannot help.");

        TransformResult result = service.transform(request("form.txt"), MODEL, 4);

        assertThat(result.getParsedBy()).isEqualTo("CODE");
        assertThat(result.getParseFallbackReason()).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void aiOnlyKeepsTheAiResult() throws Exception {
        TransformServiceImpl service = withModes("AI_ONLY");
        when(ai.sendPrompt(any(PromptEnvelope.class)))
                .thenReturn("```json\n[{\"Name\":\"Ada\"}]\n```");

        TransformResult result = service.transform(request("form.txt"), MODEL, 4);

        assertThat(result.getJsonPayload()).isEqualTo("[{\"Name\":\"Ada\"}]");
        assertThat(result.getParsedBy()).isEqualTo("AI");
        verifyNoInteractions(quota);
    }

    @Test
    void withoutTheAiTheTemplateMustBeNamed() throws Exception {
        TransformServiceImpl service = withModes(null);
        when(settings.getBoolForPlatform(SettingKeys.AI_ENABLED)).thenReturn(false);

        assertThatThrownBy(() -> service.getTemplateId(request("form.txt")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Pass templateId");
        verifyNoInteractions(prompts);
    }
}
