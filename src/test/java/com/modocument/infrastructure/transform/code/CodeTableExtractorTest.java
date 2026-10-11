package com.modocument.infrastructure.transform.code;

import com.modocument.application.dto.ParseResult;
import com.modocument.application.dto.TableExtractionHints;
import com.modocument.domain.entity.TemplateDataType;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.RuleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.modocument.infrastructure.transform.code.FieldSpec.Type.DATE;
import static com.modocument.infrastructure.transform.code.FieldSpec.Type.NUMBER;
import static com.modocument.infrastructure.transform.code.FieldSpec.Type.TEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class CodeTableExtractorTest {

    private final CodeTableExtractor extractor = ExtractionCorpus.extractor();

    private static final List<FieldSpec> PEOPLE = List.of(
            FieldSpec.of("Name", TEXT),
            FieldSpec.of("Birth Date", DATE),
            FieldSpec.of("Salary", NUMBER));

    private CodeExtraction best(String text, List<FieldSpec> fields) {
        return extractor.extract(ExtractionInput.builder().text(text).fields(fields).build()).orElseThrow();
    }

    // ---- the gate ------------------------------------------------------------

    @Test
    void acceptsAtOrAboveTheMinimumWithoutLowConfidence() {
        CodeExtraction extraction = withConfidence(0.75);

        assertThat(CodeTableExtractor.accept(extraction, 0.75, false))
                .hasValueSatisfying(e -> assertThat(e.lowConfidence()).isFalse());
        assertThat(CodeTableExtractor.accept(extraction, 0.75, true))
                .hasValueSatisfying(e -> assertThat(e.lowConfidence()).isFalse());
    }

    @Test
    void codeOnlyPassesTheMiddleBandAsLowConfidence() {
        CodeExtraction extraction = withConfidence(0.55);

        assertThat(CodeTableExtractor.accept(extraction, 0.75, false)).isEmpty();
        assertThat(CodeTableExtractor.accept(extraction, 0.75, true))
                .hasValueSatisfying(e -> assertThat(e.lowConfidence()).isTrue());
        assertThat(CodeTableExtractor.accept(withConfidence(0.40), 0.75, true)).isPresent();
    }

    @Test
    void refusesBelowTheCodeOnlyFloorInEveryMode() {
        CodeExtraction extraction = withConfidence(0.39);

        assertThat(CodeTableExtractor.accept(extraction, 0.75, false)).isEmpty();
        assertThat(CodeTableExtractor.accept(extraction, 0.75, true)).isEmpty();
        assertThat(CodeTableExtractor.accept(null, 0.75, true)).isEmpty();
    }

    @Test
    void aHalfMatchingTableIsLowConfidenceAndOnlyPassesInCodeOnlyMode() {
        // Two of four fields, and the dates are mostly not dates.
        String csv = "Name,Birth Date,Region,Notes\n"
                + "Ann,1990-01-02,North,ok\nBen,unknown,South,ok\nCid,n/a,East,ok\n";
        List<FieldSpec> fields = List.of(FieldSpec.of("Name", TEXT), FieldSpec.of("Birth Date", DATE),
                FieldSpec.of("Salary", NUMBER), FieldSpec.of("Account Number", TEXT));
        ExtractionInput input = ExtractionInput.builder().text(csv).fields(fields).build();

        CodeExtraction extraction = extractor.extract(input).orElseThrow();

        assertThat(extraction.confidence()).isBetween(CodeTableExtractor.CODE_ONLY_FLOOR, 0.749);
        assertThat(extractor.extract(input, CodeTableExtractor.DEFAULT_MIN_CONFIDENCE, false)).isEmpty();
        assertThat(extractor.extract(input, CodeTableExtractor.DEFAULT_MIN_CONFIDENCE, true))
                .hasValueSatisfying(e -> assertThat(e.lowConfidence()).isTrue());
    }

    @Test
    void proseIsRefusedEvenInCodeOnlyMode() {
        String prose = "Dear customer, thank you for banking with us. Your request has been received,\n"
                + "and an adviser will call you within two working days. Kind regards, the team.\n";
        ExtractionInput input = ExtractionInput.builder().text(prose).fields(PEOPLE).build();

        assertThat(extractor.extract(input, CodeTableExtractor.DEFAULT_MIN_CONFIDENCE, true)).isEmpty();
    }

    // ---- strategies ----------------------------------------------------------

    @Test
    void readsPipeTablesWithoutTheStrictHintGates() {
        String markdown = "Quarterly staff\n\n| Name | Birth Date | Salary |\n|---|---|---|\n"
                + "| Ann | 1990-01-02 | 1,000.00 |\n| Ben | 02/14/1985 | 2,500.50 |\n| Cid | 1979-07-08 | 300 |\n";

        CodeExtraction extraction = best(markdown, PEOPLE);

        assertThat(extraction.strategy()).isEqualTo(CodeStrategy.MARKDOWN_TABLE);
        assertThat(extraction.rows()).hasSize(3);
        assertThat(extraction.rows().get(1)).containsEntry("Birth Date", "1985-02-14").containsEntry("Salary", "2500.50");
    }

    @Test
    void readsWorkbookRowLinesWithTheHeaderBelowTitleRows() {
        String rows = "Row 1: Staff report\t\t\nRow 2: \t\t\nRow 3: Name\tBirth Date\tSalary\n"
                + "Row 4: Ann\t1990-01-02\t1000\nRow 5: Ben\t1985-05-06\t2500\nRow 6: Cid\t1979-07-08\t300\n";

        CodeExtraction extraction = best(rows, PEOPLE);

        assertThat(extraction.strategy()).isEqualTo(CodeStrategy.ROW_LINE);
        assertThat(extraction.headers()).containsExactly("Name", "Birth Date", "Salary");
        assertThat(extraction.rows()).extracting(r -> r.get("Name")).containsExactly("Ann", "Ben", "Cid");
    }

    @Test
    void sniffsPipeAndTabDelimiters() {
        String pipe = "Name|Birth Date|Salary\nAnn|1990-01-02|1000\nBen|1985-05-06|2500\nCid|1979-07-08|300\n";
        String tab = pipe.replace('|', '\t');

        assertThat(best(pipe, PEOPLE).notes()).contains("delimiter pipe");
        assertThat(best(tab, PEOPLE).notes()).contains("delimiter TAB");
        assertThat(best(tab, PEOPLE).confidence()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void readsFixedWidthTextWithRaggedRightEdges() {
        String report = String.join("\n",
                "STAFF REPORT",
                "Name           Birth Date     Salary   Notes",
                "Ann Lee        1990-01-02     1,000    ",
                "Ben Okafor     1985-05-06     2,500    transferred",
                "Cid            1979-07-08       300",
                "Dee Ramdin     1993-03-04    12,000    new");

        CodeExtraction extraction = best(report, PEOPLE);

        assertThat(extraction.strategy()).isEqualTo(CodeStrategy.FIXED_WIDTH);
        assertThat(extraction.rows()).hasSize(4);
        assertThat(extraction.rows().get(1)).containsEntry("Name", "Ben Okafor").containsEntry("Notes", "transferred");
        assertThat(extraction.rows().get(3)).containsEntry("Salary", "12000");
    }

    @Test
    void readsEverySupportedLabelSeparator() {
        List<FieldSpec> fields = List.of(FieldSpec.of("Customer", TEXT), FieldSpec.of("Branch", TEXT),
                FieldSpec.of("Currency", TEXT), FieldSpec.of("Reference", TEXT),
                FieldSpec.of("Opened", DATE), FieldSpec.of("Balance", NUMBER), FieldSpec.of("Email", TEXT));
        String form = String.join("\n",
                "Customer: Jane Doe",
                "Branch - Port Louis",
                "Currency = MUR",
                "Reference\tREF-9",
                "Opened",
                "15/01/2024",
                "Balance      1,250.00",
                "Email:",
                "jane@example.com");

        CodeExtraction extraction = best(form, fields);

        assertThat(extraction.strategy()).isEqualTo(CodeStrategy.KEY_VALUE);
        assertThat(extraction.rows()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("Customer", "Jane Doe").containsEntry("Branch", "Port Louis")
                .containsEntry("Currency", "MUR").containsEntry("Reference", "REF-9")
                .containsEntry("Opened", "2024-01-15").containsEntry("Balance", "1250.00")
                .containsEntry("Email", "jane@example.com"));
    }

    @Test
    void matchesLabelsThroughFieldAndGlobalAliases() {
        List<FieldSpec> fields = List.of(
                FieldSpec.of("Employee Number", TEXT),
                FieldSpec.of("Full Name", TEXT).withAliases(List.of("Nom")),
                FieldSpec.of("DateOfBirth", DATE));
        // "DOB" reaches DateOfBirth through ColumnNameSimilarity's built-in alias table.
        String form = "Matricule: 4417\nNom: Ravi Seeparsad\nDOB: 1990-04-03\n";

        CodeExtraction extraction = extractor.extract(ExtractionInput.builder().text(form).fields(fields)
                .globalAliases(Map.of("matricule", "employeenumber")).build()).orElseThrow();

        assertThat(extraction.rows()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("Employee Number", "4417")
                .containsEntry("Full Name", "Ravi Seeparsad")
                .containsEntry("DateOfBirth", "1990-04-03"));
    }

    @Test
    void splitsRepeatedBlocksAtPageBreaksAndCopiesThePreamble() {
        List<FieldSpec> fields = List.of(FieldSpec.of("Account", TEXT), FieldSpec.of("Payee", TEXT),
                FieldSpec.of("Amount", NUMBER));
        // Without the page break, Alpha and 20.00 would share a record.
        String text = "Account: 001\nPayee: Alpha\n\fAmount: 20.00\n\fPayee: Gamma\nAmount: 30.00\n";

        CodeExtraction extraction = best(text, fields);

        assertThat(extraction.rows()).hasSize(3);
        assertThat(extraction.rows()).extracting(r -> r.get("Account")).containsOnly("001");
        assertThat(extraction.rows()).extracting(r -> r.get("Payee")).containsExactly("Alpha", "", "Gamma");
        assertThat(extraction.rows()).extracting(r -> r.get("Amount")).containsExactly("", "20.00", "30.00");
    }

    @Test
    void ignoresAMaliciousHintPatternAndKeepsTheOthers() {
        List<FieldSpec> fields = List.of(
                FieldSpec.of("Code", TEXT).withValuePattern("(\\w+\\s?)+$"),
                FieldSpec.of("Amount", NUMBER).withValuePattern("USD (\\d+\\.\\d{2})"));
        String text = "Paid USD 10.00 today\nPaid USD 20.50 later\n";

        CodeExtraction extraction = best(text, fields);

        assertThat(extraction.strategy()).isEqualTo(CodeStrategy.FIELD_REGEX);
        assertThat(extraction.rows()).extracting(r -> r.get("Amount")).containsExactly("10.00", "20.50");
        assertThat(extraction.notes()).anySatisfy(note -> assertThat(note)
                .contains("ignored the value pattern of field 'Code'"));
    }

    @Test
    void rejoinsAmountsSplitByAnUnquotedThousandsSeparator() {
        List<FieldSpec> fields = List.of(FieldSpec.of("Name", TEXT), FieldSpec.of("Amount", NUMBER),
                FieldSpec.of("Balance", NUMBER));
        String csv = "Name,Amount,Balance\nAnn,1,234.56,2,000.00\nBen,12.00,1,012.00\nCid,3.50,15.50\n";

        CodeExtraction extraction = best(csv, fields);

        assertThat(extraction.rows()).extracting(r -> r.get("Amount")).containsExactly("1234.56", "12.00", "3.50");
        assertThat(extraction.rows()).extracting(r -> r.get("Balance")).containsExactly("2000.00", "1012.00", "15.50");
        assertThat(extraction.notes()).anySatisfy(note -> assertThat(note).startsWith("rejoined 2 rows"));
    }

    @Test
    void rowsWiderThanTheHeaderAreReportedAndLowerTheScore() {
        String clean = "Name,Birth Date,Salary\nAnn,1990-01-02,1000\nBen,1985-05-06,2500\nCid,1979-07-08,300\n";
        String spilled = "Name,Birth Date,Salary\nAnn,1990-01-02,1000,x,y\nBen,1985-05-06,2500,z\nCid,1979-07-08,300\n";

        CodeExtraction extraction = best(spilled, PEOPLE);

        assertThat(extraction.notes()).contains("2 rows had more cells than the header; the extra cells were left out");
        assertThat(extraction.breakdown().structure()).isLessThan(best(clean, PEOPLE).breakdown().structure());
    }

    @Test
    void worksWithoutTemplateFields() {
        String csv = "Name;City;Joined\nAnn;Moka;2020-01-02\nBen;Curepipe;2021-03-04\nCid;Flacq;2019-05-06\n";

        CodeExtraction extraction = best(csv, List.of());

        assertThat(extraction.strategy()).isEqualTo(CodeStrategy.DELIMITED);
        assertThat(extraction.suggestedMapping()).isEmpty();
        assertThat(extraction.confidence()).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    void readsTheNativeSheetAndPrefersItOnATie(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("people.csv");
        Files.writeString(csv, "Name,Birth Date,Salary\nAnn,1990-01-02,1000\nBen,1985-05-06,2500\n");

        CodeExtraction extraction = extractor.extract(ExtractionInput.builder()
                .text(Files.readString(csv)).file(csv).fields(PEOPLE).build()).orElseThrow();

        assertThat(extraction.strategy()).isEqualTo(CodeStrategy.NATIVE_SHEET);
        assertThat(extraction.rows()).hasSize(2);
    }

    // ---- scoring, output shape, privacy ---------------------------------------

    @Test
    void rowCountAgreementIsNeutralWithoutAMatchingHint() {
        List<String> headers = List.of("Name", "Salary");
        TableExtractionHints same = new TableExtractionHints(".csv", "local", 2, List.of("Name", "Salary"), 100);
        TableExtractionHints other = new TableExtractionHints(".csv", "local", 2, List.of("Foo", "Bar"), 100);

        assertThat(CandidateScorer.rowAgreement(105, same, headers)).isEqualTo(1.0);
        assertThat(CandidateScorer.rowAgreement(130, same, headers)).isCloseTo(0.5, offset(0.001));
        assertThat(CandidateScorer.rowAgreement(160, same, headers)).isZero();
        assertThat(CandidateScorer.rowAgreement(160, other, headers)).isNull();
        assertThat(CandidateScorer.rowAgreement(160, null, headers)).isNull();
    }

    @Test
    void validationRulesFeedTheShapeScore() {
        String csv = "Name,Birth Date,Salary\nAnn,1990-01-02,1000\nBen,1985-05-06,2500\nCid,1979-07-08,300\n";
        ValidationRule onlyAnn = new ValidationRule(1, "Name", RuleType.REGEX, "{\"Pattern\":\"^Ann$\"}");
        ExtractionInput input = ExtractionInput.builder().text(csv).fields(PEOPLE)
                .rulesByField(Map.of("Name", List.of(onlyAnn))).build();

        CodeExtraction withRule = extractor.extract(input).orElseThrow();
        CodeExtraction without = best(csv, PEOPLE);

        assertThat(withRule.breakdown().shape()).isLessThan(without.breakdown().shape());
    }

    @Test
    void producesTheParseResultShapeAndKeepsValuesOutOfNotes() {
        String csv = "Name,Birth Date,Salary\nZebediah Quill,1990-01-02,1000\nBen,1985-05-06,2500\n";

        CodeExtraction extraction = best(csv, PEOPLE);
        ParseResult result = extraction.toParseResult();

        assertThat(result.table().getColumns()).containsExactly("Name", "Birth Date", "Salary");
        assertThat(result.table().getRows()).hasSize(2);
        assertThat(result.suggestedMapping()).containsEntry("Birth Date", "Birth Date");
        assertThat(String.join(" ", extraction.notes())).doesNotContain("Zebediah").doesNotContain("1990");
    }

    @Test
    void fieldSpecFromATemplateFieldCarriesTheDataType() {
        TemplateFields field = new TemplateFields();
        field.setFieldName("Hire Date");
        field.setTemplateDataTypeId(3);
        assertThat(FieldSpec.from(field)).isEqualTo(FieldSpec.of("Hire Date", DATE));

        field.setTemplateDataType(new TemplateDataType(2, "Number"));
        assertThat(FieldSpec.from(field).type()).isEqualTo(NUMBER);
    }

    @Test
    void staysFastOnLargeTablesAndStatements() {
        StringBuilder csv = new StringBuilder("Name;Birth Date;Salary\n");
        StringBuilder blocks = new StringBuilder("Account: 001\n");
        for (int i = 0; i < 5_000; i++) {
            csv.append("Person ").append(i).append(';').append(1 + i % 28).append('/')
                    .append(1 + i % 12).append("/1990;").append(i).append(".000,50\n");
            blocks.append("Payee: P").append(i).append("\nAmount: ").append(i).append(".50\n\n");
        }
        List<FieldSpec> statement = List.of(FieldSpec.of("Account", TEXT), FieldSpec.of("Payee", TEXT),
                FieldSpec.of("Amount", NUMBER));

        assertTimeout(Duration.ofSeconds(15), () -> {
            CodeExtraction table = best(csv.toString(), PEOPLE);
            assertThat(table.rows()).hasSize(5_000);
            assertThat(table.rows().get(20)).containsEntry("Birth Date", "1990-09-21").containsEntry("Salary", "20000.50");
            assertThat(best(blocks.toString(), statement).rows()).hasSize(5_000);
        });
    }

    @Test
    void emptyInputHasNoExtraction() {
        assertThat(extractor.extract(ExtractionInput.builder().text("").fields(PEOPLE).build()))
                .isEqualTo(Optional.empty());
    }

    private static CodeExtraction withConfidence(double confidence) {
        return new CodeExtraction(List.of("A"), List.of(Map.of("A", "1")), Map.of(), CodeStrategy.DELIMITED,
                confidence, false, new CodeExtraction.Confidence(confidence, confidence, confidence, null),
                List.of());
    }
}
