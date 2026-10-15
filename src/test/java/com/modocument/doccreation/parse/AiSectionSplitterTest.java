package com.modocument.doccreation.parse;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSectionSplitterTest {

    private static final int USER = 1;

    @TempDir
    Path tempDir;

    private final AiFilesService ai = mock(AiFilesService.class);
    // Real limiter with a generous window: quota behavior is covered separately.
    private final LlmRateLimiter quota = new LlmRateLimiter(1000, 3_600_000, 100);
    private final AiSectionSplitter splitter = new AiSectionSplitter(ai, quota, () -> USER, new PiiTokenizer(new PrivacyProperties()), true, "");

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
        when(ai.sendForObject(any(PromptEnvelope.class), any())).thenReturn(sections);
    }

    @org.junit.jupiter.api.BeforeEach
    void aiIsConfigured() {
        when(ai.isConfigured()).thenReturn(true);
    }

    @Test
    void acceptsAValidBreakdownAndAnchorsItToTheRealDocument() {
        aiReturns(List.of(
                new AiSectionSplitter.AiSection(1, "Roof Overview", 1, List.of("roof", "tiles"), null),
                new AiSectionSplitter.AiSection(4, "DC Routing", 2, List.of("dc2", "cable"), null)));

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
        aiReturns(List.of(new AiSectionSplitter.AiSection(3, "A table is not a heading", 1, List.of(), null)));
        assertThat(splitter.split(docx)).isEmpty();

        aiReturns(List.of(new AiSectionSplitter.AiSection(99, "Out of range", 1, List.of(), null)));
        assertThat(splitter.split(docx)).isEmpty();
    }

    @Test
    void putsOutOfOrderAnchorsInDocumentOrderAndDropsRepeats() {
        aiReturns(List.of(
                new AiSectionSplitter.AiSection(4, "DC Routing", 1, List.of(), null),
                new AiSectionSplitter.AiSection(1, "Roof", 1, List.of(), null)));
        assertThat(splitter.split(docx)).hasValueSatisfying(sections -> assertThat(sections)
                .extracting(ParsedSection::anchorParagraphIndex).containsExactly(1, 4));

        aiReturns(List.of(
                new AiSectionSplitter.AiSection(1, "Roof", 1, List.of(), null),
                new AiSectionSplitter.AiSection(1, "Roof again", 1, List.of(), null)));
        assertThat(splitter.split(docx)).hasValueSatisfying(sections -> assertThat(sections)
                .extracting(ParsedSection::title).containsExactly("Roof"));
    }

    @Test
    void aMostlyWrongReplyIsRejectedAsAWhole() {
        aiReturns(List.of(
                new AiSectionSplitter.AiSection(1, "Roof", 1, List.of(), null),
                new AiSectionSplitter.AiSection(3, "A table", 1, List.of(), null),
                new AiSectionSplitter.AiSection(99, "Out of range", 1, List.of(), null)));
        assertThat(splitter.split(docx)).isEmpty();
    }

    @Test
    void anUnconfiguredOrFailingModelMeansFallBackNotFailTheUpload() {
        // doThrow keeps the re-stub from invoking the throwing stub itself.
        doThrow(new AiNotConfiguredException("no key"))
                .when(ai).sendForObject(any(PromptEnvelope.class), any());
        assertThat(splitter.split(docx)).isEmpty();

        doThrow(new IllegalStateException("provider down"))
                .when(ai).sendForObject(any(PromptEnvelope.class), any());
        assertThat(splitter.split(docx)).isEmpty();
    }

    @Test
    void anEmptyReplyMeansFallBack() {
        aiReturns(List.of());
        assertThat(splitter.split(docx)).isEmpty();
    }

    @Test
    void anUnconfiguredDeploymentSpendsNoQuotaSlot() {
        when(ai.isConfigured()).thenReturn(false);
        LlmRateLimiter oneSlot = new LlmRateLimiter(1, 3_600_000, 100);
        AiSectionSplitter unconfigured = new AiSectionSplitter(ai, oneSlot, () -> USER, new PiiTokenizer(new PrivacyProperties()), true, "");

        assertThat(unconfigured.split(docx)).isEmpty();
        oneSlot.check(USER); // the slot was NOT consumed by the skipped AI call
    }

    @Test
    void disabledSplitterNeverCallsTheModel() {
        AiSectionSplitter disabled = new AiSectionSplitter(ai, quota, () -> USER, new PiiTokenizer(new PrivacyProperties()), false, "");

        assertThat(disabled.split(docx)).isEmpty();
        verify(ai, never()).sendForObject(any(PromptEnvelope.class), any());
    }

    @Test
    void anExhaustedQuotaDegradesToTheHeuristicInsteadOfFailing() {
        LlmRateLimiter exhausted = new LlmRateLimiter(1, 3_600_000, 100);
        exhausted.check(USER); // spend the only permit
        AiSectionSplitter limited = new AiSectionSplitter(ai, exhausted, () -> USER, new PiiTokenizer(new PrivacyProperties()), true, "");

        assertThat(limited.split(docx)).isEmpty();
        verify(ai, never()).sendForObject(any(PromptEnvelope.class), any());
    }

    @Test
    void aDocumentWithoutParagraphTextNeverCallsTheModel() throws IOException {
        Path empty = tempDir.resolve("empty.docx");
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(empty)) {
            doc.createParagraph();
            doc.createParagraph().createRun().setText("   ");
            doc.write(out);
        }

        assertThat(splitter.split(empty)).isEmpty();
        verify(ai, never()).sendForObject(any(PromptEnvelope.class), any());
    }

    @Test
    void aLongDocumentIsSentAsACondensedHeadingListingWithRealIndexes() throws IOException {
        Path big = tempDir.resolve("big.docx");
        String body = "This paragraph is ordinary body text describing the findings in some detail, "
                + "long enough that the whole listing runs past the AI listing cap. ";
        int scopeIndex;
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(big)) {
            doc.createParagraph().createRun().setText("1. Introduction");
            for (int i = 0; i < 200; i++) {
                doc.createParagraph().createRun().setText(body + java.util.UUID.randomUUID() + " "
                        + java.util.UUID.randomUUID() + " " + java.util.UUID.randomUUID());
            }
            doc.createParagraph().createRun().setText("2. Scope of works");
            scopeIndex = doc.getBodyElements().size() - 1;
            for (int i = 0; i < 200; i++) {
                doc.createParagraph().createRun().setText(body + java.util.UUID.randomUUID() + " "
                        + java.util.UUID.randomUUID() + " " + java.util.UUID.randomUUID());
            }
            doc.write(out);
        }
        org.mockito.ArgumentCaptor<PromptEnvelope> prompt = org.mockito.ArgumentCaptor.forClass(PromptEnvelope.class);
        when(ai.sendForObject(prompt.capture(), any())).thenReturn(List.of(
                new AiSectionSplitter.AiSection(0, "Introduction", 1, List.of(), null),
                new AiSectionSplitter.AiSection(scopeIndex, "Scope of works", 1, List.of(), null)));

        Optional<List<ParsedSection>> result = splitter.split(big);

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(ParsedSection::anchorParagraphIndex).containsExactly(0, scopeIndex);
        String sent = prompt.getValue().text();
        assertThat(sent).contains("ONLY the lines most likely to be headings");
        assertThat(sent).contains("[" + scopeIndex + "] 2. Scope of works");
        assertThat(sent).doesNotContain("ordinary body text");
    }

    @Test
    void headingCandidatesAreShortStructuredLines() {
        try (XWPFDocument doc = new XWPFDocument()) {
            assertThat(candidate(doc, "3.1 Earthing")).isTrue();
            assertThat(candidate(doc, "SCHEDULE OF RATES")).isTrue();
            assertThat(candidate(doc, "Annexure B - Declarations")).isTrue();
            assertThat(candidate(doc, "Applicant details:")).isTrue();
            assertThat(candidate(doc, "The applicant confirms that the information given in this form is "
                    + "true and complete to the best of their knowledge and belief.")).isFalse();
            assertThat(candidate(doc, "")).isFalse();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static boolean candidate(XWPFDocument doc, String text) {
        org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph = doc.createParagraph();
        paragraph.createRun().setText(text);
        return AiSectionSplitter.isHeadingCandidate(paragraph);
    }

    @Test
    void digestListsParagraphsWithIndexesAndMarksTables() throws IOException {
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(docx))) {
            String digest = AiSectionSplitter.digest(List.copyOf(doc.getBodyElements()));

            assertThat(digest).contains("[0] Letterhead line.");
            assertThat(digest).contains("[1] Roof Overview");
            assertThat(digest).contains("[3] <TABLE cols=");
            assertThat(digest).contains("[4] DC Routing (DC2)");
        }
    }
}
