package com.modocument.doccreation.parse;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SectionCoverageValidatorTest {

    @Test
    void fullCoverageWhenSectionsSpanTheBody() throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Intro");
            doc.createParagraph().createRun().setText("Body one");
            doc.createParagraph().createRun().setText("Body two");
            var elements = List.copyOf(doc.getBodyElements());
            List<ParsedSection> sections = List.of(
                    new ParsedSection(0, "Intro", 1, 0, "Intro", List.of()),
                    new ParsedSection(1, "Tail", 1, 2, "Body two", List.of()));
            SectionCoverageValidator.Result result = SectionCoverageValidator.compute(sections, elements);
            assertThat(result.coveragePercent()).isEqualTo(100);
            assertThat(result.gaps()).isEmpty();
        }
    }

    @Test
    void gapFillAddsSectionsAtUncoveredRanges() throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Start");
            doc.createParagraph().createRun().setText("Orphan");
            doc.createParagraph().createRun().setText("End");
            var elements = List.copyOf(doc.getBodyElements());
            List<ParsedSection> sections = List.of(
                    new ParsedSection(0, "Start", 1, 0, "Start", List.of()),
                    new ParsedSection(1, "End", 1, 2, "End", List.of()));
            SectionCoverageValidator.Result before = SectionCoverageValidator.compute(sections, elements);
            assertThat(before.coveragePercent()).isLessThan(100);
            List<ParsedSection> filled = SectionCoverageValidator.gapFillSections(sections, before.gaps(), elements);
            SectionCoverageValidator.Result after = SectionCoverageValidator.compute(filled, elements);
            assertThat(after.coveragePercent()).isGreaterThan(before.coveragePercent());
        }
    }
}
