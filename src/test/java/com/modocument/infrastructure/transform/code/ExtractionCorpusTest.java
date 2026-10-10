package com.modocument.infrastructure.transform.code;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden corpus (see {@link ExtractionCorpus}): each generated document
 * must come back through the expected strategy, inside its confidence range,
 * with at least 95% of the golden cells exact, and pass the default gate.
 */
class ExtractionCorpusTest {

    private static final Logger log = LoggerFactory.getLogger(ExtractionCorpusTest.class);
    private static final Map<String, String> SUMMARY = new ConcurrentSkipListMap<>();

    private final CodeTableExtractor extractor = ExtractionCorpus.extractor();

    @TempDir
    Path tempDir;

    static List<String> cases() {
        return ExtractionCorpus.CASES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void reproducesTheGoldenTable(String name) throws IOException {
        ExtractionCorpus.Case golden = ExtractionCorpus.load(name);
        Path file = ExtractionCorpus.generate(golden.file(), tempDir);
        ExtractionInput input = ExtractionCorpus.input(golden, file);

        CodeExtraction best = extractor.extract(input).orElseThrow();
        double accuracy = ExtractionCorpus.cellAccuracy(golden, best);
        SUMMARY.put(name, String.format("%s %.3f accuracy %.1f%% rows %d/%d", best.strategy(),
                best.confidence(), accuracy * 100, best.rows().size(), golden.rows().size()));

        assertThat(best.strategy()).as("strategy; notes %s", best.notes()).isEqualTo(golden.strategy());
        assertThat(accuracy).as("cell accuracy; rows %s", best.rows()).isGreaterThanOrEqualTo(0.95);
        assertThat(best.rows()).hasSize(golden.rows().size());
        assertThat(best.confidence()).as("confidence %s", best.breakdown())
                .isBetween(golden.minConfidence(), golden.maxConfidence());
        assertThat(CodeTableExtractor.accept(best, CodeTableExtractor.DEFAULT_MIN_CONFIDENCE, false))
                .hasValueSatisfying(accepted -> assertThat(accepted.lowConfidence()).isFalse());
        assertThat(best.toParseResult().table().getColumns()).isEqualTo(best.headers());
    }

    /**
     * AC-EXT-08 over the whole corpus: at least 40 documents (this corpus
     * plus QA's), at least 95 % of all golden cells exact, and at least 90 %
     * per strategy — reported per strategy so a regression shows where.
     */
    @Test
    void theCorpusMeetsTheAccuracyBarOverallAndPerStrategy() throws IOException {
        assertThat(ExtractionCorpus.CASES.size() + QaExtractionCorpusTest.CASES.size())
                .as("documents in the golden corpus (engineers' + QA's)").isGreaterThanOrEqualTo(40);

        Map<CodeStrategy, int[]> byStrategy = new EnumMap<>(CodeStrategy.class);
        for (String name : ExtractionCorpus.CASES) {
            ExtractionCorpus.Case golden = ExtractionCorpus.load(name);
            Path file = ExtractionCorpus.generate(golden.file(),
                    java.nio.file.Files.createDirectories(tempDir.resolve(name)));
            CodeExtraction best = extractor.extract(ExtractionCorpus.input(golden, file)).orElseThrow();
            int cells = Math.max(golden.rows().size(), best.rows().size()) * golden.fields().size();
            int matched = (int) Math.round(ExtractionCorpus.cellAccuracy(golden, best) * cells);
            int[] counts = byStrategy.computeIfAbsent(golden.strategy(), s -> new int[2]);
            counts[0] += matched;
            counts[1] += cells;
        }
        int matched = 0;
        int cells = 0;
        for (Map.Entry<CodeStrategy, int[]> entry : byStrategy.entrySet()) {
            int[] counts = entry.getValue();
            double accuracy = (double) counts[0] / counts[1];
            log.info("extraction corpus strategy {}: {}/{} cells ({}%)", entry.getKey(), counts[0], counts[1],
                    String.format("%.1f", accuracy * 100));
            assertThat(accuracy).as("cell accuracy of %s", entry.getKey()).isGreaterThanOrEqualTo(0.90);
            matched += counts[0];
            cells += counts[1];
        }
        assertThat((double) matched / cells).as("overall cell accuracy").isGreaterThanOrEqualTo(0.95);
    }

    @AfterAll
    static void printSummary() {
        SUMMARY.forEach((name, line) -> log.info("extraction corpus {}: {}", name, line));
    }
}
