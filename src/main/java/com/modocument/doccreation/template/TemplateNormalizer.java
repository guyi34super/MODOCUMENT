package com.modocument.doccreation.template;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.doccreation.parse.AiSectionSplitter;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strips specimen/sample values from an uploaded template while keeping labels
 * and layout (V292). Heuristics always run when enabled; AI-assisted CLEAR ops
 * run only when the bank's AI switches allow it.
 */
@Component
public class TemplateNormalizer {

    private static final Logger log = LoggerFactory.getLogger(TemplateNormalizer.class);

    private static final Pattern SPECIMEN_LINE = Pattern.compile(
            "(?i)^(\\s*(specimen|fictional|mock|sample only|for demonstration|not for use)\\s*[.!]?\\s*)$");
    private static final Pattern SPECIMEN_INLINE = Pattern.compile(
            "(?i)(specimen|fictional customer|mock data|sample only)");

    private static final String AI_PROMPT = """
            You are cleaning a document template: remove filled-in SAMPLE values but keep \
            labels, headings, and layout. Each line is "[index] text" (paragraph) or a table line.

            For each paragraph index, reply with {"index":N,"action":"KEEP"|"CLEAR"|"CLEAR_AFTER_LABEL"}:
            - KEEP: leave unchanged
            - CLEAR: remove all text in that paragraph (watermarks, specimen banners)
            - CLEAR_AFTER_LABEL: for label/value rows (Label\\tValue or "Label: value"), keep the \
            label and clear the value side only

            Reply with ONLY a JSON array. Never invent indexes.

            Document listing:
            """;

    private final AiFilesService ai;
    private final LlmRateLimiter llmQuota;
    private final CurrentUserProvider currentUser;
    private final PiiTokenizer tokenizer;
    private final boolean aiEnabledDefault;
    private final String modelId;
    private final SettingsReader settings;

    public TemplateNormalizer(AiFilesService ai,
                              LlmRateLimiter llmQuota,
                              CurrentUserProvider currentUser,
                              PiiTokenizer tokenizer,
                              @Value("${modocument.doccreation.section-ai-enabled:true}") boolean aiEnabledDefault,
                              @Value("${modocument.doccreation.section-model-id:}") String modelId,
                              @Autowired(required = false) SettingsReader settings) {
        this.ai = ai;
        this.llmQuota = llmQuota;
        this.currentUser = currentUser;
        this.tokenizer = tokenizer;
        this.aiEnabledDefault = aiEnabledDefault;
        this.modelId = modelId;
        this.settings = settings;
    }

    public record Result(byte[] bytes, int clearedCount, boolean aiAssisted) {
    }

    public static boolean looksFilledFilename(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return false;
        }
        String lower = originalName.toLowerCase(Locale.ROOT);
        return lower.contains("_filled") || lower.contains("specimen") || lower.contains("_sample")
                || lower.contains("-sample") || lower.contains("_mock");
    }

    public Result normalize(byte[] docxBytes, String originalFileName, boolean stripSampleData) {
        if (!stripSampleData || docxBytes == null || docxBytes.length == 0) {
            return new Result(docxBytes, 0, false);
        }
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            List<IBodyElement> elements = List.copyOf(doc.getBodyElements());
            int heuristic = applyHeuristics(elements);
            boolean aiAssisted = false;
            if (heuristic < 3 && aiConfigured()) {
                aiAssisted = applyAiClears(elements);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            int total = heuristic + (aiAssisted ? 1 : 0);
            if (total > 0) {
                log.info("Template normalize '{}': {} heuristic clears{}", originalFileName, heuristic,
                        aiAssisted ? " + AI assist" : "");
            }
            return new Result(out.toByteArray(), total, aiAssisted);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not normalize the template.", e);
        }
    }

    private boolean aiConfigured() {
        if (settings != null) {
            return settings.getBoolForCurrentBank(SettingKeys.DOCCREATION_SECTION_AI_ENABLED)
                    && settings.getBoolForCurrentBank(SettingKeys.AI_ENABLED)
                    && ai.isConfigured();
        }
        return aiEnabledDefault && ai.isConfigured();
    }

    private int applyHeuristics(List<IBodyElement> elements) {
        int cleared = 0;
        for (IBodyElement element : elements) {
            if (!(element instanceof XWPFParagraph paragraph)) {
                continue;
            }
            String text = paragraph.getText() == null ? "" : paragraph.getText();
            if (text.isBlank()) {
                continue;
            }
            if (SPECIMEN_LINE.matcher(text.strip()).matches()) {
                clearParagraph(paragraph);
                cleared++;
                continue;
            }
            if (SPECIMEN_INLINE.matcher(text).find() && text.length() < 120) {
                clearParagraph(paragraph);
                cleared++;
                continue;
            }
            int tab = text.indexOf('\t');
            if (tab > 0 && text.substring(tab + 1).strip().length() > 0) {
                clearAfterLabel(paragraph, text.substring(0, tab).strip(), '\t');
                cleared++;
                continue;
            }
            int colon = text.indexOf(':');
            if (colon > 0 && colon < 60 && text.substring(colon + 1).strip().length() > 0
                    && !text.strip().endsWith(":")) {
                clearAfterLabel(paragraph, text.substring(0, colon).strip(), ':');
                cleared++;
            }
        }
        return cleared;
    }

    private boolean applyAiClears(List<IBodyElement> elements) {
        String digest = AiSectionSplitter.digest(elements);
        if (digest.contains(AiSectionSplitter.TRUNCATION_MARKER)) {
            return false;
        }
        PiiTokenizer.Session session = tokenizer.newSession();
        List<CleanOp> ops;
        try {
            llmQuota.check(currentUser.requireUserId());
            ops = ai.sendForObject(
                    PromptEnvelope.builder(AiPurpose.DOC_TEMPLATE_CLEAN)
                            .user(currentUser.requireUserId())
                            .part(AI_PROMPT + session.tokenizeText(digest))
                            .model(modelId)
                            .build(),
                    new ParameterizedTypeReference<>() {
                    });
        } catch (RuntimeException e) {
            log.warn("AI template clean unavailable: {}", e.getMessage());
            return false;
        }
        if (ops == null || ops.isEmpty()) {
            return false;
        }
        boolean applied = false;
        for (CleanOp op : ops) {
            if (op == null || op.index() == null || op.action() == null) {
                continue;
            }
            int index = op.index();
            if (index < 0 || index >= elements.size()
                    || !(elements.get(index) instanceof XWPFParagraph paragraph)) {
                continue;
            }
            String action = op.action().strip().toUpperCase(Locale.ROOT);
            switch (action) {
                case "CLEAR" -> {
                    clearParagraph(paragraph);
                    applied = true;
                }
                case "CLEAR_AFTER_LABEL" -> {
                    String text = paragraph.getText() == null ? "" : paragraph.getText();
                    if (text.contains("\t")) {
                        clearAfterLabel(paragraph, text.substring(0, text.indexOf('\t')).strip(), '\t');
                    } else if (text.contains(":")) {
                        clearAfterLabel(paragraph, text.substring(0, text.indexOf(':')).strip(), ':');
                    }
                    applied = true;
                }
                default -> {
                }
            }
        }
        return applied;
    }

    private static void clearParagraph(XWPFParagraph paragraph) {
        for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) {
            paragraph.removeRun(i);
        }
        paragraph.createRun().setText("");
    }

    private static void clearAfterLabel(XWPFParagraph paragraph, String label, char separator) {
        String full = paragraph.getText() == null ? "" : paragraph.getText();
        String prefix = label + separator;
        if (!full.strip().startsWith(label) && !full.contains(String.valueOf(separator))) {
            return;
        }
        for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) {
            paragraph.removeRun(i);
        }
        XWPFRun run = paragraph.createRun();
        run.setText(prefix);
    }

    public record CleanOp(Integer index, String action) {
    }
}
