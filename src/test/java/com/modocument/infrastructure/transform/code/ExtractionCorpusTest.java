package com.modocument.infrastructure.transform.code;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
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

    @AfterAll
    static void printSummary() {
        SUMMARY.forEach((name, line) -> log.info("extraction corpus {}: {}", name, line));
    }
}
