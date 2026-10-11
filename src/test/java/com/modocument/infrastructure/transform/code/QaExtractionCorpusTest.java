package com.modocument.infrastructure.transform.code;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA golden corpus for the code extraction engine (AC-EXT-08, curated by QA).
 * It sits next to the engineers' {@link ExtractionCorpusTest} (generated
 * PDF/DOCX/XLSX/CSV cases) and adds the text shapes a bank actually drops:
 *
 * <ul>
 *   <li>comma CSV with a UTF-8 BOM, CRLF, a title line, quoted commas, a
 *       quoted newline, escaped quotes, an empty trailing cell and totals;</li>
 *   <li>semicolon CSV with decimal commas, dotted dates, a quoted newline
 *       and a quoted ';';</li>
 *   <li>tab-separated text; a markdown pipe table inside prose;</li>
 *   <li>a fixed-width text report with an underline row and a totals line;</li>
 *   <li>repeated {@code Label: value} blocks with an alias-only label;</li>
 *   <li>an XLSX with the header on row 3 and typed date/number cells
 *       (generated here: no binary fixtures in the repository);</li>
 *   <li>a NEGATIVE case: prose with numbers must not pass the default gate
 *       (risk #3 — code fallback silently producing wrong data), and neither
 *       may a clean table whose columns do not belong to the template.</li>
 * </ul>
 *
 * Fixtures: {@code src/test/resources/extraction-corpus/qa/files/} (exact
 * bytes, {@code -text} in the local .gitattributes); expectations:
 * {@code .../qa/expected/*.json}. For every positive case the test asserts
 * an allowed strategy, full field coverage (every template field mapped to a
 * column), ≥ 95 % exact cells, the golden row count, and a confidence at or
 * above the case's minimum (default {@link CodeTableExtractor#DEFAULT_MIN_CONFIDENCE}),
 * accepted by the gate without the low-confidence flag.
 */
class QaExtractionCorpusTest {

    private static final Logger log = LoggerFactory.getLogger(QaExtractionCorpusTest.class);
    private static final Map<String, String> SUMMARY = new ConcurrentSkipListMap<>();
    private static final String ROOT = "/extraction-corpus/qa/";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static final List<String> CASES = List.of(
            "loan-book-bom-csv",
            "fx-settlements-semicolon-csv",
            "branch-cash-tabs-txt",
            "kyc-review-markdown-txt",
            "cheques-fixed-width-txt",
            "beneficiaries-key-value-txt",
            "accounts-xlsx",
            "memo-no-table-txt",
            "unrelated-columns-csv");

    /** One QA case: like {@link ExtractionCorpus.Case}, with a set of acceptable strategies. */
    record QaCase(String name,
                  String file,
                  List<CodeStrategy> strategies,
                  double minConfidence,
                  boolean expectAccepted,
                  List<FieldSpec> fields,
                  Map<String, String> globalAliases,
                  List<List<String>> rows) {

        ExtractionCorpus.Case asGolden() {
            return new ExtractionCorpus.Case(name, file,
                    strategies == null || strategies.isEmpty() ? null : strategies.get(0),
                    minConfidence, 1.0, fields, globalAliases, rows);
        }
    }

    private final CodeTableExtractor extractor = ExtractionCorpus.extractor();

    @TempDir
    Path tempDir;

    static List<String> cases() {
        return CASES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void meetsTheGoldenTable(String name) throws IOException {
        QaCase golden = load(name);
        Path file = materialize(golden.file());
        ExtractionCorpus.Case asGolden = golden.asGolden();
        ExtractionInput input = ExtractionCorpus.input(asGolden, file);

        Optional<CodeExtraction> best = extractor.extract(input);

        if (!golden.expectAccepted()) {
            Optional<CodeExtraction> accepted = best.flatMap(extraction -> CodeTableExtractor.accept(
                    extraction, CodeTableExtractor.DEFAULT_MIN_CONFIDENCE, false));
            SUMMARY.put(name, best.map(b -> String.format("%s %.3f (must be refused)",
                    b.strategy(), b.confidence())).orElse("no candidate (refused)"));
            assertThat(accepted).as("a document with no table must not pass the gate: %s",
                    best.map(CodeExtraction::breakdown).orElse(null)).isEmpty();
            return;
        }

        assertThat(best).as("some strategy must find rows").isPresent();
        CodeExtraction extraction = best.get();
        double accuracy = ExtractionCorpus.cellAccuracy(asGolden, extraction);
        double coverage = fieldCoverage(golden, extraction);
        SUMMARY.put(name, String.format("%s %.3f coverage %.0f%% accuracy %.1f%% rows %d/%d",
                extraction.strategy(), extraction.confidence(), coverage * 100, accuracy * 100,
                extraction.rows().size(), golden.rows().size()));

        assertThat(golden.strategies()).as("strategy; notes %s", extraction.notes())
                .contains(extraction.strategy());
        assertThat(coverage).as("field coverage; mapping %s", extraction.suggestedMapping())
                .isEqualTo(1.0);
        assertThat(accuracy).as("cell accuracy; rows %s", extraction.rows())
                .isGreaterThanOrEqualTo(0.95);
        assertThat(extraction.rows()).hasSize(golden.rows().size());
        assertThat(extraction.confidence()).as("confidence %s", extraction.breakdown())
                .isGreaterThanOrEqualTo(Math.max(golden.minConfidence(),
                        CodeTableExtractor.DEFAULT_MIN_CONFIDENCE));
        assertThat(CodeTableExtractor.accept(extraction, CodeTableExtractor.DEFAULT_MIN_CONFIDENCE,
                false)).hasValueSatisfying(accepted -> assertThat(accepted.lowConfidence()).isFalse());
    }

    @AfterAll
    static void printSummary() {
        SUMMARY.forEach((name, line) -> log.info("QA extraction corpus {}: {}", name, line));
    }

    /** Share of the template's fields the extraction mapped to a column. */
    private static double fieldCoverage(QaCase golden, CodeExtraction extraction) {
        Set<String> mapped = new HashSet<>(extraction.suggestedMapping().values());
        long covered = golden.fields().stream().filter(f -> mapped.contains(f.name())).count();
        return golden.fields().isEmpty() ? 0 : (double) covered / golden.fields().size();
    }

    private static QaCase load(String name) {
        String resource = ROOT + "expected/" + name + ".json";
        try (InputStream in = QaExtractionCorpusTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing golden file " + resource);
            }
            return JSON.readValue(in, QaCase.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copies a committed fixture (or generates the XLSX) into the temp dir. */
    private Path materialize(String fileName) throws IOException {
        Path target = tempDir.resolve(fileName);
        if (fileName.endsWith(".xlsx")) {
            accountsWorkbook(target);
            return target;
        }
        try (InputStream in = QaExtractionCorpusTest.class.getResourceAsStream(
                ROOT + "files/" + fileName)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + fileName);
            }
            Files.copy(in, target);
        }
        return target;
    }

    /** Title row, blank row, header on row 3, typed cells, a trailing notes row. */
    private static void accountsWorkbook(Path file) throws IOException {
        Object[][] rows = {
                {"AC-9001", "Asha Ramdin", "2020-01-15", 15000.5, "ACTIVE"},
                {"AC-9002", "Kevin Li", "2021-06-30", 250.0, "ACTIVE"},
                {"AC-9003", "Elodie Moreau", "2019-11-02", 98765.43, "DORMANT"},
                {"AC-9004", "Sunita Bhujun", "2022-03-08", 0.0, "CLOSED"},
                {"AC-9005", "Wai Ming Chan", "2023-09-21", 1234567.89, "ACTIVE"},
                {"AC-9006", "Priya Naidoo", "2018-05-05", 42.0, "ACTIVE"}};
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("Accounts");
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat()
                    .getFormat("yyyy-mm-dd"));
            sheet.createRow(0).createCell(0).setCellValue("Customer accounts - extract");
            Row header = sheet.createRow(2);
            String[] headers = {"Account Id", "Holder", "Opened", "Balance", "Status"};
            for (int c = 0; c < headers.length; c++) {
                header.createCell(c).setCellValue(headers[c]);
            }
            int r = 3;
            for (Object[] values : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue((String) values[0]);
                row.createCell(1).setCellValue((String) values[1]);
                var opened = row.createCell(2);
                opened.setCellValue(Date.from(LocalDate.parse((String) values[2]).atStartOfDay()
                        .toInstant(ZoneOffset.UTC)));
                opened.setCellStyle(dateStyle);
                row.createCell(3).setCellValue((Double) values[3]);
                row.createCell(4).setCellValue((String) values[4]);
            }
            sheet.createRow(r + 1).createCell(0).setCellValue("Balances in MUR as of 31 May 2024");
            workbook.write(out);
        }
    }
}
