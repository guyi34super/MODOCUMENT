package com.modocument.infrastructure.transform;

import com.modocument.application.dto.ExtractionPromptInput;
import com.modocument.application.dto.TemplateIdentifierPromptInput;
import com.modocument.application.dto.TransformRequest;
import com.modocument.application.dto.TransformResult;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.port.TransformService;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.prompt.PromptFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

@Service
public class TransformServiceImpl implements TransformService {

    private static final Logger log = LoggerFactory.getLogger(TransformServiceImpl.class);

    private final PromptFactory promptFactory;
    private final AiFilesService aiFilesService;
    private final DocumentParser documentParser;
    private final UploadStorage uploadStorage;
    private final PiiTokenizer tokenizer;

    public TransformServiceImpl(PromptFactory promptFactory,
                                AiFilesService aiFilesService,
                                DocumentParser documentParser,
                                UploadStorage uploadStorage,
                                PiiTokenizer tokenizer) {
        this.promptFactory = promptFactory;
        this.aiFilesService = aiFilesService;
        this.documentParser = documentParser;
        this.uploadStorage = uploadStorage;
        this.tokenizer = tokenizer;
    }

    @Override
    public TransformResult transform(TransformRequest request, String model) {
        Path filePath = resolveUpload(request);
        if (!Files.exists(filePath)) {
            throw new NoSuchElementException(
                    "File '" + request.getFileName() + "' not found in uploads folder.");
        }

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

    @Override
    public int getTemplateId(TransformRequest request) {
        Path filePath = resolveUpload(request);

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

    private Path resolveUpload(TransformRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request must be provided.");
        }
        if (request.getFileName() == null || request.getFileName().isBlank()) {
            throw new IllegalArgumentException("FileName must be provided.");
        }
        // UploadStorage.resolve rejects names that escape the uploads folder
        // ("../..."), closing the path-traversal hole a raw resolve left open.
        return uploadStorage.resolve(request.getFileName());
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
