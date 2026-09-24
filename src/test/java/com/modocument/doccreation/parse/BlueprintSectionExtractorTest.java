package com.modocument.doccreation.parse;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlueprintSectionExtractorTest {

    @TempDir
    Path tempDir;

    private final AiSectionSplitter aiSplitter = mock(AiSectionSplitter.class);
    private final BlueprintSectionExtractor extractor =
            new BlueprintSectionExtractor(aiSplitter, new BlueprintSectionParser());

    private Path docx;

    @BeforeEach
    void buildFixture() throws IOException {
        docx = tempDir.resolve("report.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph heading = doc.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Styled Heading");
            doc.createParagraph().createRun().setText("Body.");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }
    }

    @Test
    void aiBreakdownWinsWhenAvailable() {
        List<ParsedSection> aiSections = List.of(
                new ParsedSection(0, "AI Section", 1, 0, "Styled Heading", List.of("ai")));
        when(aiSplitter.split(docx)).thenReturn(Optional.of(aiSections));

        BlueprintSectionExtractor.Extraction result = extractor.extract(docx, "fallback");

        assertThat(result.source()).isEqualTo(BlueprintSectionExtractor.SOURCE_AI);
        assertThat(result.sections()).isEqualTo(aiSections);
    }

    @Test
    void heuristicParserCarriesTheUploadWhenAiStepsAside() {
        when(aiSplitter.split(docx)).thenReturn(Optional.empty());

        BlueprintSectionExtractor.Extraction result = extractor.extract(docx, "fallback");

        assertThat(result.source()).isEqualTo(BlueprintSectionExtractor.SOURCE_HEURISTIC);
        assertThat(result.sections()).singleElement()
                .satisfies(s -> assertThat(s.title()).isEqualTo("Styled Heading"));
    }
}
