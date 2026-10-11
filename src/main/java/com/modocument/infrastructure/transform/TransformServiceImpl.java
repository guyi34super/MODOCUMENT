package com.modocument.infrastructure.transform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.ExtractionPromptInput;
import com.modocument.application.dto.ParseProvenance;
import com.modocument.application.dto.TemplateIdentifierPromptInput;
import com.modocument.application.dto.TransformRequest;
import com.modocument.application.dto.TransformResult;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.TenantContext;
import com.modocument.application.port.TransformService;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.ExtractionMode;
import com.modocument.domain.model.FallbackReason;
import com.modocument.infrastructure.ai.AiFailureClassifier;
import com.modocument.infrastructure.ai.AiPreflight;
import com.modocument.infrastructure.ai.LlmExtractionFailedException;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.prompt.PromptFactory;
import com.modocument.infrastructure.transform.code.CodeExtraction;
import com.modocument.infrastructure.transform.code.FieldSpec;
import com.modocument.infrastructure.transform.code.TextDecoding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The legacy document-to-JSON transform ({@code /api/transform}). ws23: the
 * template's / bank's {@link ExtractionMode} applies as in the ingestion
 * parse stage — CODE_ONLY never calls the AI, AI_ONLY is the original
 * behaviour, and AI_WITH_CODE_FALLBACK hands the document to the
 * {@link com.modocument.infrastructure.transform.code.CodeTableExtractor}
 * (label: value text, tables) when the AI is skipped or fails. Template
 * detection is AI-only; without the AI, the caller names the template.
 */
@Service
public class TransformServiceImpl implements TransformService {

    private static final Logger log = LoggerFactory.getLogger(TransformServiceImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PromptFactory promptFactory;
    private final AiFilesService aiFilesService;
    private final DocumentParser documentParser;
    private final UploadStorage uploadStorage;
    private final PiiTokenizer tokenizer;
    private final CodeParseSupport codeParse;
    private final ExtractionModeResolver modes;
    private final AiPreflight preflight;
    private final TemplateRepository templates;
    private final TemplateFieldsRepository templateFields;
    private final CurrentUserProvider currentUser;

    /** The AI-only transform (no code fallback) — unit tests. */
    public TransformServiceImpl(PromptFactory promptFactory,
                                AiFilesService aiFilesService,
                                DocumentParser documentParser,
                                UploadStorage uploadStorage,
                                PiiTokenizer tokenizer) {
        this(promptFactory, aiFilesService, documentParser, uploadStorage, tokenizer,
                null, null, null, null, null, null);
    }

    @Autowired
    public TransformServiceImpl(PromptFactory promptFactory,
                                AiFilesService aiFilesService,
                                DocumentParser documentParser,
                                UploadStorage uploadStorage,
                                PiiTokenizer tokenizer,
                                CodeParseSupport codeParse,
                                ExtractionModeResolver modes,
                                AiPreflight preflight,
                                TemplateRepository templates,
                                TemplateFieldsRepository templateFields,
                                CurrentUserProvider currentUser) {
        this.promptFactory = promptFactory;
        this.aiFilesService = aiFilesService;
        this.documentParser = documentParser;
        this.uploadStorage = uploadStorage;
        this.tokenizer = tokenizer;
        this.codeParse = codeParse;
        this.modes = modes;
        this.preflight = preflight;
        this.templates = templates;
        this.templateFields = templateFields;
        this.currentUser = currentUser;
    }

    @Override
    public TransformResult transform(TransformRequest request, String model) {
        return transform(request, model, null);
    }

    @Override
    public TransformResult transform(TransformRequest request, String model, Integer templateId) {
        Path filePath = localUpload(resolveUpload(request));
        Integer bankId = TenantContext.currentBankId().orElse(null);
        ExtractionMode mode = mode(templateId, bankId);
        if (mode == ExtractionMode.AI_ONLY) {
            return transformWithAi(filePath, model).withProvenance(ParseProvenance.ai(null));
        }
        if (mode == ExtractionMode.CODE_ONLY) {
            return transformWithCode(filePath, request, model, templateId, bankId,
                    FallbackReason.MODE_CODE_ONLY, true);
        }
        Optional<FallbackReason> skip = preflight.check(bankId, userId());
        if (skip.isPresent()) {
            return transformWithCode(filePath, request, model, templateId, bankId, skip.get(), false);
        }
        try {
            TransformResult result = transformWithAi(filePath, model);
            if (result.getJsonPayload() == null || result.getJsonPayload().isBlank()) {
                throw new LlmExtractionFailedException("The AI reply held no JSON block.");
            }
            return result.withProvenance(ParseProvenance.ai(null));
        } catch (RuntimeException aiFailure) {
            FallbackReason reason = AiFailureClassifier.classify(aiFailure);
            log.warn("AI transform failed ({}: {}); falling back to code extraction",
                    reason, aiFailure.getClass().getSimpleName());
            return transformWithCode(filePath, request, model, templateId, bankId, reason, false);
        }
    }

    @Override
    public int getTemplateId(TransformRequest request) {
        String fileName = resolveUpload(request);
        Integer bankId = TenantContext.currentBankId().orElse(null);
        ExtractionMode mode = mode(null, bankId);
        if (mode == ExtractionMode.CODE_ONLY) {
            throw templateNeeded(FallbackReason.MODE_CODE_ONLY);
        }
        if (mode == ExtractionMode.AI_WITH_CODE_FALLBACK) {
            Optional<FallbackReason> skip = preflight.check(bankId, userId());
            if (skip.isPresent()) {
                throw templateNeeded(skip.get());
            }
            Path filePath = localUpload(fileName);
            try {
                return classify(filePath);
            } catch (RuntimeException aiFailure) {
                throw templateNeeded(AiFailureClassifier.classify(aiFailure));
            }
        }
        return classify(localUpload(fileName));
    }

    private int classify(Path filePath) {
        // The TemplateIdentifier prompt masks the snippet itself (one-way,
        // nothing to detokenize — the answer is a template number).
        PromptStrategy strategy = promptFactory.getPrompt("TemplateIdentifier");
        String prompt = strategy.buildPrompt(new TemplateIdentifierPromptInput(filePath.toString()));

        String llmResponse = aiFilesService.sendPrompt(
                PromptEnvelope.of(AiPurpose.TEMPLATE_CLASSIFY, prompt));

        int classification = Integer.parseInt(llmResponse.trim());
        log.info("LLM classified as: {}", classification);
        return classification;
    }

    /** A 400 at the API: detection needs the AI, so the caller must name the template. */
    private static UnsupportedOperationException templateNeeded(FallbackReason reason) {
        return new UnsupportedOperationException("The template could not be detected without "
                + "the AI assistant (" + reason.description() + "). Pass templateId in the "
                + "request to extract the document with code-based extraction.");
    }

    private TransformResult transformWithAi(Path filePath, String model) {
        // C9: the document's full text is tokenized before it enters the
        // prompt, and the placeholders are swapped back in the extracted
        // JSON — the model restructures tokens, never raw identifiers.
        PiiTokenizer.Session session = tokenizer.newSession();
        String fileContent = session.tokenizeText(documentParser.extractText(filePath));

        PromptStrategy strategy = promptFactory.getPrompt("Extraction");
        String prompt = strategy.buildPrompt(new ExtractionPromptInput(fileContent, model));

        String llmResponse = aiFilesService.sendPrompt(
                PromptEnvelope.of(AiPurpose.TRANSFORM_EXTRACTION, prompt));

        String extractedJson = session.detokenize(extractBetween(llmResponse, "```json", "```"));
        return new TransformResult(extractedJson);
    }

    /**
     * The document as a JSON array of {field: value} records, extracted by
     * code. A 400 at the API when code cannot read it with enough confidence.
     */
    private TransformResult transformWithCode(Path filePath, TransformRequest request, String model,
                                              Integer templateId, Integer bankId,
                                              FallbackReason reason, boolean codeOnly) {
        String text = readText(filePath);
        List<FieldSpec> fields = templateId != null && templateFields != null
                ? CodeParseSupport.fieldSpecs(templateFields.listByTemplateId(templateId))
                : fieldsFromModel(model);
        double minConfidence = modes.minConfidence(bankId);
        CodeParseSupport.Outcome outcome = codeParse.extract(
                codeParse.input(templateId == null ? 0 : templateId, fields, text, filePath, null),
                minConfidence, codeOnly);
        if (outcome.accepted().isEmpty()) {
            throw new UnsupportedOperationException("Code-based extraction could not read '"
                    + request.getFileName() + "' with enough confidence (" + outcome.describeBest()
                    + (reason == FallbackReason.MODE_CODE_ONLY ? ")."
                    : "; the AI was not used: " + reason.description() + ")."));
        }
        CodeExtraction extraction = outcome.accepted().get();
        log.info("Transform extracted by code ({}): {} at {}", reason, extraction.strategy(),
                String.format(Locale.ROOT, "%.2f", extraction.confidence()));
        return new TransformResult(toJson(extraction)).withProvenance(ParseProvenance.code(
                extraction.strategy().name(), extraction.confidence(), reason));
    }

    /** The document's text; a CSV/TXT the UTF-8 reader refuses is re-decoded (windows-1252). */
    private String readText(Path filePath) {
        try {
            return documentParser.extractText(filePath);
        } catch (UncheckedIOException unreadable) {
            try {
                return TextDecoding.decode(Files.readAllBytes(filePath)).text();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Field names from the controller's model (a JSON array of names), all TEXT. */
    private static List<FieldSpec> fieldsFromModel(String model) {
        if (model == null || model.isBlank()) {
            return List.of();
        }
        try {
            List<String> names = MAPPER.readValue(model, new TypeReference<List<String>>() {
            });
            List<FieldSpec> fields = new ArrayList<>();
            for (String name : names) {
                if (name != null && !name.isBlank()) {
                    fields.add(FieldSpec.of(name, FieldSpec.Type.TEXT));
                }
            }
            return fields;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** Rows keyed by template field (the extractor's column → field mapping); unmapped columns dropped. */
    private static String toJson(CodeExtraction extraction) {
        Map<String, String> mapping = extraction.suggestedMapping();
        List<Map<String, String>> records = new ArrayList<>();
        for (Map<String, String> row : extraction.rows()) {
            Map<String, String> record = new LinkedHashMap<>();
            for (String column : extraction.headers()) {
                String field = mapping.isEmpty() ? column : mapping.get(column);
                if (field != null) {
                    record.putIfAbsent(field, row.getOrDefault(column, ""));
                }
            }
            records.add(record);
        }
        try {
            return MAPPER.writeValueAsString(records);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the extracted records.", e);
        }
    }

    /** The template's own extraction mode, else the bank default; AI_ONLY without the ws23 helpers. */
    private ExtractionMode mode(Integer templateId, Integer bankId) {
        if (modes == null) {
            return ExtractionMode.AI_ONLY;
        }
        ExtractionMode templateMode = null;
        if (templateId != null && templates != null) {
            templateMode = templates.getById(templateId)
                    .map(Template::getExtractionMode)
                    .map(ExtractionMode::parseLenient)
                    .orElse(null);
        }
        return modes.resolve(templateMode, bankId);
    }

    private int userId() {
        try {
            return currentUser == null ? 0 : currentUser.requireUserId();
        } catch (RuntimeException anonymous) {
            return 0;
        }
    }

    /** The validated upload name (no traversal: UploadStorage.key refuses it). */
    private String resolveUpload(TransformRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request must be provided.");
        }
        if (request.getFileName() == null || request.getFileName().isBlank()) {
            throw new IllegalArgumentException("FileName must be provided.");
        }
        // UploadStorage.key rejects names that escape the uploads folder
        // ("../..."), closing the path-traversal hole a raw resolve left open.
        uploadStorage.key(request.getFileName());
        return request.getFileName();
    }

    /** A readable local file of the upload (FileStore.localCopy); missing → not found. */
    private Path localUpload(String fileName) {
        try {
            return uploadStorage.localCopy(fileName);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new NoSuchElementException("File '" + fileName + "' not found in uploads folder.");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file.", e);
        }
    }

    static String extractBetween(String input, String start, String end) {
        if (input == null || input.isEmpty() || start.isEmpty() || end.isEmpty()) {
            return "";
        }
        int startIndex = input.indexOf(start);
        if (startIndex < 0) {
            return "";
        }
        startIndex += start.length();
        int endIndex = input.indexOf(end, startIndex);
        if (endIndex < 0) {
            return "";
        }
        return input.substring(startIndex, endIndex).trim();
    }
}
