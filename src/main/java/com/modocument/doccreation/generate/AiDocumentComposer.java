package com.modocument.doccreation.generate;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.entity.DocImageAsset;
import com.modocument.doccreation.parse.AiSectionSplitter;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * AI-composed generation: the model is handed EVERYTHING — the template's full
 * body listing and every piece the user added (paragraphs, images, captions,
 * per section) — and returns the complete final document as an assembly plan
 * over the real template. The plan is validated so hard that the output is
 * provably "exactly like the doc": the KEEP ranges must cover every original
 * element exactly once, in order; every user block must appear exactly once;
 * nothing the model invents beyond wording is accepted. A valid plan is
 * rendered with POI (an LLM cannot emit sound .docx/PDF bytes itself); the
 * PDF then converts from that .docx as usual. Anything invalid or unavailable
 * → empty, and the deterministic assembler takes over.
 */
@Component
public class AiDocumentComposer {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentComposer.class);

    private static final String PROMPT = """
            You are an expert report editor. You are given (1) the full body listing of a company's \
            template document (.docx) — each line "[index] text" is a paragraph, "[index] <TABLE>" \
            a table — and (2) the content a user added per section: PARAGRAPH blocks (their text) \
            and IMAGE blocks (file name + optional caption).

            Produce the COMPLETE final document as an ordered list of operations. It must be \
            EXACTLY the template document with the user's content merged in at the right places:
            - {"op":"KEEP","from":A,"to":B} keeps original elements A..B (inclusive) untouched. \
            Across the whole plan the KEEP ranges must cover EVERY original index exactly once, \
            in ascending order, with no gaps and no overlaps — the template is reproduced as-is.
            - {"op":"PARAGRAPH","blockId":N,"text":"..."} inserts user paragraph block N at that \
            point. Include every PARAGRAPH block exactly once. You may polish its wording into \
            the document's professional tone (grammar, flow), but keep every fact and detail; \
            never drop, summarize away, or invent information.
            - {"op":"IMAGE","assetId":N,"caption":"..."} inserts user image N at that point, \
            exactly once per image. Keep the user's caption (you may fix obvious typos); write a \
            short factual caption from the file name only when none was given.
            - Place each block inside its own section — after that section's heading and its \
            existing body text, before the next section starts — in the order the user arranged \
            them, unless the content plainly reads better elsewhere within the same section.
            - Add NOTHING else: no new headings, no commentary, no content of your own.

            Reply with ONLY the JSON operations list.

            === TEMPLATE DOCUMENT ===
            """;

    private final AiFilesService ai;
    private final LlmRateLimiter llmQuota;
    private final CurrentUserProvider currentUser;
    private final DocumentAssembler assembler;
    private final PiiTokenizer tokenizer;
    private final boolean enabled;
    private final String modelId;

    public AiDocumentComposer(AiFilesService ai,
                              LlmRateLimiter llmQuota,
                              CurrentUserProvider currentUser,
                              DocumentAssembler assembler,
                              PiiTokenizer tokenizer,
                              @Value("${modocument.doccreation.generate-ai-enabled:true}") boolean enabled,
                              @Value("${modocument.doccreation.generate-model-id:}") String modelId) {
        this.ai = ai;
        this.llmQuota = llmQuota;
        this.currentUser = currentUser;
        this.assembler = assembler;
        this.tokenizer = tokenizer;
        this.enabled = enabled;
        this.modelId = modelId;
    }

    /** The op shape the model must return; nothing is trusted before validation. */
    public record AiOp(String op, Integer from, Integer to, Integer blockId, Integer assetId,
                       String text, String caption) {
    }

    /**
     * @return the AI-composed .docx bytes, or empty when AI is disabled,
     *     unavailable, over quota, or its plan fails validation — the caller
     *     then uses the deterministic assembler.
     */
    public Optional<byte[]> compose(Path blueprintDocx,
                                    List<DocBlueprintSection> sectionsInOrder,
                                    Map<Integer, List<DocContentBlock>> blocksBySectionId,
                                    Map<Integer, DocImageAsset> assetsById,
                                    Function<Integer, byte[]> assetBytesLoader) {
        if (!enabled || !ai.isConfigured()) {
            return Optional.empty();
        }
        boolean nothingToMerge = sectionsInOrder.stream()
                .allMatch(s -> blocksBySectionId
                        .getOrDefault(s.getDocBlueprintSectionId(), List.<DocContentBlock>of()).isEmpty());
        if (nothingToMerge) {
            return Optional.empty(); // nothing to merge — the plain engine reproduces the doc
        }

        // C9: identifier shapes in the template listing and in the user's
        // own paragraphs/captions travel as reversible [ID_1]-style tokens;
        // the validated plan is detokenized below, so the rendered document
        // still carries the real values while the model never saw them.
        PiiTokenizer.Session session = tokenizer.newSession();
        Map<Integer, List<DocContentBlock>> tokenizedBySection = new HashMap<>();
        List<DocContentBlock> allBlocks = new ArrayList<>();
        for (DocBlueprintSection section : sectionsInOrder) {
            List<DocContentBlock> tokenized = blocksBySectionId
                    .getOrDefault(section.getDocBlueprintSectionId(), List.<DocContentBlock>of())
                    .stream()
                    .map(block -> tokenizedCopy(block, session))
                    .toList();
            tokenizedBySection.put(section.getDocBlueprintSectionId(), tokenized);
            allBlocks.addAll(tokenized);
        }

        int elementCount;
        String prompt;
        try (InputStream in = Files.newInputStream(blueprintDocx);
             XWPFDocument doc = new XWPFDocument(in)) {
            List<IBodyElement> elements = List.copyOf(doc.getBodyElements());
            elementCount = elements.size();
            String digest = AiSectionSplitter.digest(elements);
            if (digest.contains(AiSectionSplitter.TRUNCATION_MARKER)) {
                // A cut listing can never yield a full-coverage plan; skip the
                // wasted call and let the deterministic engine generate.
                log.info("Blueprint too large for the AI listing; using the deterministic engine");
                return Optional.empty();
            }
            prompt = PROMPT + session.tokenizeText(digest)
                    + userContentDigest(sectionsInOrder, tokenizedBySection, assetsById, session);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the blueprint document.", e);
        }

        List<AiOp> reply;
        try {
            // Same per-user LLM quota as the other AI endpoints; exhaustion
            // degrades to the deterministic engine instead of failing generate.
            llmQuota.check(currentUser.requireUserId());
            reply = ai.sendForObject(
                    PromptEnvelope.builder(AiPurpose.DOC_COMPOSE)
                            .user(currentUser.requireUserId())
                            .part(prompt)
                            .model(modelId)
                            .build(),
                    new ParameterizedTypeReference<List<AiOp>>() {
                    });
        } catch (RuntimeException e) {
            log.warn("AI document composition unavailable, using the deterministic engine: {}",
                    e.getMessage());
            return Optional.empty();
        }

        Optional<List<PlanOp>> plan = validate(reply, elementCount, allBlocks, assetsById);
        return plan.map(ops -> assembler.render(
                blueprintDocx, detokenize(ops, session), assetsById, assetBytesLoader));
    }

    /** Detached copy whose text/caption carry tokens instead of identifiers. */
    private static DocContentBlock tokenizedCopy(DocContentBlock block, PiiTokenizer.Session session) {
        DocContentBlock copy = new DocContentBlock();
        copy.setDocContentBlockId(block.getDocContentBlockId());
        copy.setSectionId(block.getSectionId());
        copy.setBlockType(block.getBlockType());
        copy.setAssetId(block.getAssetId());
        copy.setTextContent(session.tokenizeText(block.getTextContent()));
        copy.setCaption(session.tokenizeText(block.getCaption()));
        return copy;
    }

    /** Swaps the per-request tokens back into the validated plan's text ops. */
    private static List<PlanOp> detokenize(List<PlanOp> ops, PiiTokenizer.Session session) {
        return ops.stream().map(op -> switch (op.kind()) {
            case KEEP -> op;
            case PARAGRAPH -> PlanOp.paragraph(session.detokenize(op.text()));
            case IMAGE -> PlanOp.image(op.assetId(),
                    op.caption() == null ? null : session.detokenize(op.caption()));
        }).toList();
    }

    /** Everything the user added, section by section, in their arranged order. */
    static String userContentDigest(List<DocBlueprintSection> sections,
                                    Map<Integer, List<DocContentBlock>> blocksBySectionId,
                                    Map<Integer, DocImageAsset> assetsById) {
        StringBuilder out = new StringBuilder("\n=== USER CONTENT PER SECTION ===\n");
        for (DocBlueprintSection section : sections) {
            List<DocContentBlock> blocks =
                    blocksBySectionId.getOrDefault(section.getDocBlueprintSectionId(), List.of());
            if (blocks.isEmpty()) {
                continue;
            }
            out.append("SECTION \"").append(section.getTitle())
                    .append("\" (heading at original index ").append(section.getAnchorParagraphIndex())
                    .append("):\n");
            for (DocContentBlock block : blocks) {
                if (DocContentBlock.TYPE_PARAGRAPH.equals(block.getBlockType())) {
                    out.append("- PARAGRAPH blockId=").append(block.getDocContentBlockId())
                            .append(": ").append(block.getTextContent() == null ? "" : block.getTextContent())
                            .append('\n');
                } else {
                    DocImageAsset asset = assetsById.get(block.getAssetId());
                    out.append("- IMAGE assetId=").append(block.getAssetId())
                            .append(" file=").append(asset == null ? "?" : asset.getOriginalFileName());
                    if (block.getCaption() != null && !block.getCaption().isBlank()) {
                        out.append(" caption=").append(block.getCaption());
                    }
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    /**
     * The exactness gate: KEEP ranges must tile [0, elementCount) in order;
     * every user block appears exactly once; no invented content. Missing text
     * or captions fall back to the block's own — the model can only relocate
     * and rephrase, never lose or add.
     */
    private static Optional<List<PlanOp>> validate(List<AiOp> reply, int elementCount,
                                                   List<DocContentBlock> allBlocks,
                                                   Map<Integer, DocImageAsset> assetsById) {
        if (reply == null || reply.isEmpty()) {
            return invalid("empty plan");
        }
        Map<Integer, DocContentBlock> paragraphBlocksById = new HashMap<>();
        Map<Integer, DocContentBlock> imageBlocksByAssetId = new HashMap<>();
        for (DocContentBlock block : allBlocks) {
            if (DocContentBlock.TYPE_PARAGRAPH.equals(block.getBlockType())) {
                paragraphBlocksById.put(block.getDocContentBlockId(), block);
            } else if (block.getAssetId() != null) {
                imageBlocksByAssetId.put(block.getAssetId(), block);
            }
        }

        List<PlanOp> ops = new ArrayList<>();
        int nextExpectedIndex = 0;
        Set<Integer> placedParagraphs = new HashSet<>();
        Set<Integer> placedAssets = new HashSet<>();
        for (AiOp op : reply) {
            if (op == null || op.op() == null) {
                return invalid("an operation without an op type");
            }
            switch (op.op().toUpperCase(java.util.Locale.ROOT)) {
                case "KEEP" -> {
                    if (op.from() == null || op.to() == null) {
                        return invalid("a KEEP without from/to");
                    }
                    if (op.from() != nextExpectedIndex || op.to() < op.from()
                            || op.to() >= elementCount) {
                        return invalid("KEEP " + op.from() + ".." + op.to()
                                + " breaks exact coverage (expected to continue at "
                                + nextExpectedIndex + ")");
                    }
                    nextExpectedIndex = op.to() + 1;
                    ops.add(PlanOp.keep(op.from(), op.to()));
                }
                case "PARAGRAPH" -> {
                    DocContentBlock block = op.blockId() == null ? null
                            : paragraphBlocksById.get(op.blockId());
                    if (block == null || !placedParagraphs.add(block.getDocContentBlockId())) {
                        return invalid("paragraph blockId " + op.blockId()
                                + " is unknown, duplicated, or invented");
                    }
                    ops.add(PlanOp.paragraph(boundedRewrite(op.text(), block.getTextContent())));
                }
                case "IMAGE" -> {
                    DocContentBlock block = op.assetId() == null ? null
                            : imageBlocksByAssetId.get(op.assetId());
                    if (block == null || assetsById.get(op.assetId()) == null
                            || !placedAssets.add(op.assetId())) {
                        return invalid("image assetId " + op.assetId()
                                + " is unknown, duplicated, or invented");
                    }
                    String caption = op.caption() == null || op.caption().isBlank()
                            ? block.getCaption() : boundedRewrite(op.caption(), block.getCaption());
                    if (caption != null && caption.length() > 500) {
                        caption = caption.substring(0, 500);
                    }
                    ops.add(PlanOp.image(op.assetId(), caption));
                }
                default -> {
                    return invalid("unknown op '" + op.op() + "'");
                }
            }
        }
        if (nextExpectedIndex != elementCount) {
            return invalid("the KEEP ranges stop at " + nextExpectedIndex + " of " + elementCount
                    + " original elements");
        }
        if (placedParagraphs.size() != paragraphBlocksById.size()) {
            return invalid("not every user paragraph was placed");
        }
        if (placedAssets.size() != imageBlocksByAssetId.size()) {
            return invalid("not every user image was placed");
        }
        return Optional.of(ops);
    }

    private static Optional<List<PlanOp>> invalid(String why) {
        log.warn("AI-composed plan rejected ({}), using the deterministic engine", why);
        return Optional.empty();
    }

    /**
     * The wording bound: the model may polish the user's text, never replace
     * it. When the rewrite no longer carries most of the user's own words
     * (which is also what a prompt-injecting template would produce), the
     * user's verbatim text wins.
     */
    static String boundedRewrite(String aiText, String userText) {
        String original = userText == null ? "" : userText;
        if (aiText == null || aiText.isBlank()) {
            return original;
        }
        Set<String> significant = significantTokens(original);
        if (significant.isEmpty()) {
            return aiText.strip();
        }
        Set<String> rewritten = significantTokens(aiText);
        long kept = significant.stream().filter(rewritten::contains).count();
        if (kept * 10 >= significant.size() * 6) { // >= 60% of the user's words survive
            return aiText.strip();
        }
        log.warn("AI rewrite dropped too much of the user's own text; keeping the original wording");
        return original;
    }

    private static Set<String> significantTokens(String text) {
        Set<String> tokens = new HashSet<>();
        for (String token : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (token.length() >= 4) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
