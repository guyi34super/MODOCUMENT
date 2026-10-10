package com.modocument.infrastructure.transform.code;

import com.modocument.application.mapping.ValueShapeScorer;
import com.modocument.application.validation.SafeRegex;
import com.modocument.infrastructure.file.MarkdownTableMaterializer;
import com.modocument.infrastructure.file.RowLineTableMaterializer;
import com.modocument.infrastructure.file.TabularNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Code-based (no-LLM) table extraction: every applicable strategy proposes
 * candidate tables, each is mapped to the template's fields, normalised and
 * scored, and the best one wins (ties go to the more structured strategy, in
 * {@link CodeStrategy} order). {@link #accept} applies the confidence gate the
 * parse step uses to decide whether the result may stand in for the LLM.
 *
 * <p>A pure library: no I/O beyond reading the given file, no network, no
 * LLM, no persistence. Cell values never reach logs or notes.
 */
@Component
public class CodeTableExtractor {

    /** Default {@code extraction.code.min-confidence}. */
    public static final double DEFAULT_MIN_CONFIDENCE = 0.75;

    /** In CODE_ONLY mode, scores from here up to the minimum pass as low confidence. */
    public static final double CODE_ONLY_FLOOR = 0.40;

    private static final Logger log = LoggerFactory.getLogger(CodeTableExtractor.class);

    private final List<ExtractionStrategy> strategies;
    private final CandidateScorer scorer;
    private final long regexBudgetMillis;

    @Autowired
    public CodeTableExtractor(TabularNormalizer tabularNormalizer,
                              MarkdownTableMaterializer markdownMaterializer,
                              RowLineTableMaterializer rowLineMaterializer,
                              ValueShapeScorer valueShapeScorer) {
        this(tabularNormalizer, markdownMaterializer, rowLineMaterializer, valueShapeScorer,
                SafeRegex.DEFAULT_DOCUMENT_BUDGET_MILLIS);
    }

    /** {@code valueShapeScorer} may be null (type checks only); the budget caps author regex time per document. */
    public CodeTableExtractor(TabularNormalizer tabularNormalizer,
                              MarkdownTableMaterializer markdownMaterializer,
                              RowLineTableMaterializer rowLineMaterializer,
                              ValueShapeScorer valueShapeScorer,
                              long regexBudgetMillis) {
        this.strategies = List.of(
                new NativeSheetStrategy(tabularNormalizer),
                new MarkdownTableStrategy(markdownMaterializer),
                new RowLineStrategy(rowLineMaterializer),
                new DelimitedStrategy(),
                new FixedWidthStrategy(),
                new KeyValueStrategy(),
                new FieldRegexStrategy());
        this.scorer = new CandidateScorer(valueShapeScorer);
        this.regexBudgetMillis = regexBudgetMillis;
    }

    /** The best extraction, whatever its confidence; empty when no strategy found any rows. */
    public Optional<CodeExtraction> extract(ExtractionInput input) {
        List<CodeExtraction> ranked = extractAll(input);
        return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0));
    }

    /** The best extraction if it passes {@link #accept}; empty otherwise. */
    public Optional<CodeExtraction> extract(ExtractionInput input, double minConfidence, boolean codeOnly) {
        return extract(input).flatMap(best -> accept(best, minConfidence, codeOnly));
    }

    /** Every candidate, scored, best first. */
    public List<CodeExtraction> extractAll(ExtractionInput input) {
        long started = System.nanoTime();
        ExtractionContext context = new ExtractionContext(input,
                SafeRegex.budget(regexBudgetMillis, SafeRegex.DEFAULT_MATCH_TIMEOUT_MILLIS));
        List<CodeExtraction> scored = new ArrayList<>();
        for (ExtractionStrategy strategy : strategies) {
            List<Candidate> candidates;
            try {
                candidates = strategy.propose(context);
            } catch (RuntimeException e) {
                log.warn("Code extraction strategy {} failed: {}", strategy.name(), e.getClass().getSimpleName());
                log.debug("Strategy failure detail", e);
                continue;
            }
            for (Candidate candidate : candidates) {
                if (candidate.rows().isEmpty() || candidate.headers().isEmpty()) {
                    continue;
                }
                try {
                    scored.add(scorer.score(candidate, context));
                } catch (RuntimeException e) {
                    log.warn("Scoring a {} candidate failed: {}", strategy.name(), e.getClass().getSimpleName());
                    log.debug("Scoring failure detail", e);
                }
            }
        }
        scored.sort(Comparator.comparingDouble(CodeExtraction::confidence).reversed()
                .thenComparing(e -> e.strategy().ordinal()));
        if (log.isDebugEnabled()) {
            log.debug("Code extraction: {} candidates in {} ms, best {} at {}",
                    scored.size(), (System.nanoTime() - started) / 1_000_000,
                    scored.isEmpty() ? "-" : scored.get(0).strategy(),
                    scored.isEmpty() ? 0 : scored.get(0).confidence());
        }
        return scored;
    }

    /**
     * The confidence gate. At or above {@code minConfidence} the extraction
     * is accepted as is. In CODE_ONLY mode (no LLM to fall back to) a score
     * from {@link #CODE_ONLY_FLOOR} up to the minimum is accepted with
     * {@code lowConfidence = true}, so the job shows a low-confidence callout
     * and still goes through validation and review. Anything else is refused.
     */
    public static Optional<CodeExtraction> accept(CodeExtraction extraction, double minConfidence, boolean codeOnly) {
        if (extraction == null) {
            return Optional.empty();
        }
        if (extraction.confidence() >= minConfidence) {
            return Optional.of(extraction.withLowConfidence(false));
        }
        if (codeOnly && extraction.confidence() >= CODE_ONLY_FLOOR) {
            return Optional.of(extraction.withLowConfidence(true));
        }
        return Optional.empty();
    }
}
