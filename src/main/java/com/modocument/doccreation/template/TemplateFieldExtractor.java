package com.modocument.doccreation.template;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.parse.AiSectionSplitter;
import com.modocument.doccreation.parse.ParsedSection;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Detects fillable fields inside FORM template sections (V292). */
@Component
public class TemplateFieldExtractor {

    private static final Logger log = LoggerFactory.getLogger(TemplateFieldExtractor.class);

    private static final String PROMPT = """
            You are mapping fillable fields in one FORM section of a document template. Lines show \
            "[index] text" with optional LABEL= markers.

            Return a JSON array of fields: {"fieldKey":"snake_case","label":"…",\
            "anchorParagraphIndex":N,"valueStyle":"INLINE"|"TAB_CELL"|"NEXT_PARAGRAPH"}.
            - anchorParagraphIndex must be one of the indexes shown below.
            - fieldKey: short unique snake_case id derived from the label.
            - valueStyle TAB_CELL when the line is Label\\tValue; INLINE when "Label: value" on one line; \
            NEXT_PARAGRAPH when the value is on the following paragraph.
            - Skip headings and boilerplate; only real data-entry slots.

            Section listing:
            """;

    private final AiFilesService ai;
    private final LlmRateLimiter llmQuota;
    private final CurrentUserProvider currentUser;
    private final PiiTokenizer tokenizer;
    private final boolean enabledDefault;
    private final String modelId;
    private final SettingsReader settings;

    public TemplateFieldExtractor(AiFilesService ai,
                                  LlmRateLimiter llmQuota,
                                  CurrentUserProvider currentUser,
                                  PiiTokenizer tokenizer,
                                  @Value("${modocument.doccreation.section-ai-enabled:true}") boolean enabledDefault,
                                  @Value("${modocument.doccreation.section-model-id:}") String modelId,
                                  @Autowired(required = false) SettingsReader settings) {
        this.ai = ai;
        this.llmQuota = llmQuota;
        this.currentUser = currentUser;
        this.tokenizer = tokenizer;
        this.enabledDefault = enabledDefault;
        this.modelId = modelId;
        this.settings = settings;
    }

    public record AiField(String fieldKey, String label, Integer anchorParagraphIndex, String valueStyle) {
    }

    public String extractSectionFields(List<IBodyElement> elements,
                                       DocBlueprintSection section,
                                       int nextAnchorIndex) {
        int start = section.getAnchorParagraphIndex();
        int end = nextAnchorIndex < 0 ? elements.size() : nextAnchorIndex;
        List<TemplateFieldSchema.FieldDef> fields = aiFields(elements, start, end)
                .orElseGet(() -> heuristicFields(elements, start, end));
        fields = TemplateFieldSchema.dedupeKeys(fields);
        return fields.isEmpty() ? null : TemplateFieldSchema.write(fields);
    }

    private Optional<List<TemplateFieldSchema.FieldDef>> aiFields(List<IBodyElement> elements,
                                                                  int start, int end) {
        if (!enabled() || !ai.isConfigured()) {
            return Optional.empty();
        }
        String slice = digestSlice(elements, start, end);
        if (slice.isBlank()) {
            return Optional.empty();
        }
        List<AiField> reply;
        try {
            PiiTokenizer.Session session = tokenizer.newSession();
            llmQuota.check(currentUser.requireUserId());
            reply = ai.sendForObject(
                    PromptEnvelope.builder(AiPurpose.DOC_TEMPLATE_FIELD)
                            .user(currentUser.requireUserId())
                            .part(PROMPT + session.tokenizeText(slice))
                            .model(modelId)
                            .build(),
                    new ParameterizedTypeReference<>() {
                    });
        } catch (RuntimeException e) {
            log.debug("AI field extract unavailable: {}", e.getMessage());
            return Optional.empty();
        }
        if (reply == null || reply.isEmpty()) {
            return Optional.empty();
        }
        List<TemplateFieldSchema.FieldDef> out = new ArrayList<>();
        for (AiField field : reply) {
            if (field == null || field.anchorParagraphIndex() == null) {
                continue;
            }
            int anchor = field.anchorParagraphIndex();
            if (anchor < start || anchor >= end || !(elements.get(anchor) instanceof XWPFParagraph)) {
                continue;
            }
            String label = field.label() == null || field.label().isBlank()
                    ? paragraphLabel((XWPFParagraph) elements.get(anchor)) : field.label().strip();
            if (label.isBlank()) {
                continue;
            }
            String key = field.fieldKey() == null || field.fieldKey().isBlank()
                    ? TemplateFieldSchema.slugKey(label) : field.fieldKey().strip().toLowerCase(Locale.ROOT);
            out.add(new TemplateFieldSchema.FieldDef(key, label, anchor,
                    field.valueStyle() == null ? inferStyle((XWPFParagraph) elements.get(anchor))
                            : field.valueStyle()));
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    private static List<TemplateFieldSchema.FieldDef> heuristicFields(List<IBodyElement> elements,
                                                                      int start, int end) {
        List<TemplateFieldSchema.FieldDef> out = new ArrayList<>();
        for (int i = start; i < end && i < elements.size(); i++) {
            if (!(elements.get(i) instanceof XWPFParagraph paragraph)) {
                continue;
            }
            String text = paragraph.getText() == null ? "" : paragraph.getText().strip();
            if (text.isBlank()) {
                continue;
            }
            String label = paragraphLabel(paragraph);
            if (label == null || label.isBlank()) {
                continue;
            }
            out.add(new TemplateFieldSchema.FieldDef(
                    TemplateFieldSchema.slugKey(label), label, i, inferStyle(paragraph)));
        }
        return out;
    }

    private static String paragraphLabel(XWPFParagraph paragraph) {
        String text = paragraph.getText() == null ? "" : paragraph.getText();
        int tab = text.indexOf('\t');
        if (tab > 0) {
            return text.substring(0, tab).strip();
        }
        if (text.endsWith(":") && text.length() <= 80) {
            return text.substring(0, text.length() - 1).strip();
        }
        int colon = text.indexOf(':');
        if (colon > 0 && colon < 60) {
            return text.substring(0, colon).strip();
        }
        return null;
    }

    private static String inferStyle(XWPFParagraph paragraph) {
        String text = paragraph.getText() == null ? "" : paragraph.getText();
        if (text.contains("\t")) {
            return TemplateFieldSchema.FieldDef.STYLE_TAB_CELL;
        }
        return TemplateFieldSchema.FieldDef.STYLE_INLINE;
    }

    private static String digestSlice(List<IBodyElement> elements, int start, int end) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < end && i < elements.size(); i++) {
            IBodyElement element = elements.get(i);
            if (element instanceof XWPFParagraph paragraph) {
                String text = paragraph.getText() == null ? "" : paragraph.getText().strip().replace('\n', ' ');
                if (text.isEmpty()) {
                    continue;
                }
                out.append('[').append(i).append("] ").append(text).append('\n');
            } else {
                out.append('[').append(i).append("] <TABLE>\n");
            }
        }
        return out.toString();
    }

    private boolean enabled() {
        if (settings != null) {
            return settings.getBoolForCurrentBank(SettingKeys.DOCCREATION_SECTION_AI_ENABLED)
                    && settings.getBoolForCurrentBank(SettingKeys.AI_ENABLED);
        }
        return enabledDefault;
    }

    /** Next section anchor after {@code section} in document order. */
    public static int nextAnchor(List<DocBlueprintSection> ordered, DocBlueprintSection section) {
        return ordered.stream()
                .filter(s -> s.getAnchorParagraphIndex() > section.getAnchorParagraphIndex())
                .map(DocBlueprintSection::getAnchorParagraphIndex)
                .min(Comparator.naturalOrder())
                .orElse(-1);
    }

    public static boolean isFormSection(DocBlueprintSection section) {
        return ParsedSection.KIND_FORM.equals(section.getSectionKind());
    }
}
