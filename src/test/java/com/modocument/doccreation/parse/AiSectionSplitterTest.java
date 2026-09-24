package com.modocument.doccreation.parse;

import com.modocument.application.port.AiFilesService;
import com.modocument.infrastructure.ai.AiNotConfiguredException;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiSectionSplitterTest {

    private static final int USER = 1;

    @TempDir
    Path tempDir;

    private final AiFilesService ai = mock(AiFilesService.class);
    // Real limiter with a generous window: quota behavior is covered separately.
    private final LlmRateLimiter quota = new LlmRateLimiter(1000, 3_600_000, 100);
    private final AiSectionSplitter splitter = new AiSectionSplitter(ai, quota, () -> USER, true, "");

    private Path docx;

    @BeforeEach
    void buildFixture() throws IOException {
        docx = tempDir.resolve("report.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Letterhead line.");       // 0
            doc.createParagraph().createRun().setText("Roof Overview");          // 1 (no style!)
            doc.createParagraph().createRun().setText("Roof body text.");        // 2
            doc.createTable(1, 1);                                               // 3
            doc.createParagraph().createRun().setText("DC Routing (DC2)");       // 4
            doc.createParagraph().createRun().setText("DC body text.");          // 5
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }
    }

    private void aiReturns(List<AiSectionSplitter.AiSection> sections) {
        when(ai.sendForObject(anyString(), any(), anyString())).thenReturn(sections);
    }

    @Test
    void acceptsAValidBreakdownAndAnchorsItToTheRealDocument() {
        aiReturns(List.of(
                new AiSectionSplitter.AiSection(1, "Roof Overview", 1, List.of("roof", "tiles")),
                new AiSectionSplitter.AiSection(4, "DC Routing", 2, List.of("dc2", "cable"))));

        Optional<List<ParsedSection>> result = splitter.split(docx);

        assertThat(result).isPresent();
        List<ParsedSection> sections = result.get();
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).anchorParagraphIndex()).isEqualTo(1);
        // Anchor text comes from the document, never from the model.
        assertThat(sections.get(0).anchorText()).isEqualTo("Roof Overview");
        assertThat(sections.get(1).anchorParagraphIndex()).isEqualTo(4);
        assertThat(sections.get(1).anchorText()).isEqualTo("DC Routing (DC2)");
        assertThat(sections.get(1).headingLevel()).isEqualTo(2);
        // AI keywords are kept and merged with the title-seeded ones.
        assertThat(sections.get(1).keywords()).contains("dc2", "cable", "routing");
        assertThat(sections.get(0).orderIndex()).isZero();
        assertThat(sections.get(1).orderIndex()).isEqualTo(1);
    }

    @Test
    void rejectsAnchorsThatAreNotRealParagraphs() {
        aiReturns(List.of(new AiSectionSplitter.AiSection(3, "A table is not a heading", 1, List.of())));
        assertThat(splitter.split(docx)).isEmpty();

        aiReturns(List.of(new AiSectionSplitter.AiSection(99, "Out of range", 1, List.of())));
        assertThat(splitter.split(docx)).isEmpty();
    }

    @Test
    void rejectsOutOfOrderOrDuplicateAnchors() {
        aiReturns(List.of(
                new AiSectionSplitter.AiSection(4, "DC Routing", 1, List.of()),
                new AiSectionSplitter.AiSection(1, "Roof", 1, List.of())));
        assertThat(splitter.split(docx)).isEmpty();

        aiReturns(List.of(
                new AiSectionSplitter.AiSection(1, "Roof", 1, List.of()),
                new AiSectionSplitter.AiSection(1, "Roof again", 1, List.of())));
        assertThat(splitter.split(docx)).isEmpty();
    }

    @Test
    void anUnconfiguredOrFailingModelMeansFallBackNotFailTheUpload() {
        // doThrow keeps the re-stub from invoking the throwing stub itself.
        doThrow(new AiNotConfiguredException("no key"))
                .when(ai).sendForObject(anyString(), any(), anyString());
        assertThat(splitter.split(docx)).isEmpty();

        doThrow(new IllegalStateException("provider down"))
                .when(ai).sendForObject(anyString(), any(), anyString());
        assertThat(splitter.split(docx)).isEmpty();
    }

    @Test
    void anEmptyReplyMeansFallBack() {
        aiReturns(List.of());
        assertThat(splitter.split(docx)).isEmpty();
    }

    @Test
    void disabledSplitterNeverCallsTheModel() {
        AiSectionSplitter disabled = new AiSectionSplitter(ai, quota, () -> USER, false, "");

        assertThat(disabled.split(docx)).isEmpty();
        verifyNoInteractions(ai);
    }

    @Test
    void anExhaustedQuotaDegradesToTheHeuristicInsteadOfFailing() {
        LlmRateLimiter exhausted = new LlmRateLimiter(1, 3_600_000, 100);
        exhausted.check(USER); // spend the only permit
        AiSectionSplitter limited = new AiSectionSplitter(ai, exhausted, () -> USER, true, "");

        assertThat(limited.split(docx)).isEmpty();
        verifyNoInteractions(ai);
    }

    @Test
    void digestListsParagraphsWithIndexesAndMarksTables() throws IOException {
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(docx))) {
            String digest = AiSectionSplitter.digest(List.copyOf(doc.getBodyElements()));

            assertThat(digest).contains("[0] Letterhead line.");
            assertThat(digest).contains("[1] Roof Overview");
            assertThat(digest).contains("[3] <TABLE>");
            assertThat(digest).contains("[4] DC Routing (DC2)");
        }
    }
}
