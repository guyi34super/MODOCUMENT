package com.modocument.doccreation.usecase;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.FileStore;
import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.dto.SaveSectionsRequest;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocBlueprintVersion;
import com.modocument.doccreation.flow.DocumentCreationFlow;
import com.modocument.doccreation.flow.DocumentCreationFlowRegistry;
import com.modocument.doccreation.parse.BlueprintSectionExtractor;
import com.modocument.doccreation.parse.ParsedSection;
import com.modocument.doccreation.pdf.LibreOfficeDocxConverter;
import com.modocument.doccreation.pdf.PdfBlueprintReflower;
import com.modocument.doccreation.template.TemplateFieldExtractor;
import com.modocument.doccreation.template.TemplateNormalizer;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocBlueprintSectionRepository;
import com.modocument.doccreation.repo.DocBlueprintVersionRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.infrastructure.file.UploadStorage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocBlueprintUseCaseTest {

    @Mock
    DocBlueprintRepository blueprints;
    @Mock
    DocBlueprintSectionRepository sections;
    @Mock
    DocBlueprintVersionRepository versions;
    @Mock
    DocInstanceRepository instances;
    @Mock
    DocCreationOwnership ownership;
    @Mock
    BlueprintSectionExtractor sectionExtractor;
    @Mock
    DocumentCreationFlowRegistry flows;
    @Mock
    UploadStorage uploadStorage;
    @Mock
    FileStore files;
    @Mock
    PdfBlueprintReflower pdfReflower;
    @Mock
    LibreOfficeDocxConverter pdfToDocx;
    @Mock
    AuditRecorder audit;
    @Mock
    TemplateNormalizer templateNormalizer;
    @Mock
    TemplateFieldExtractor fieldExtractor;

    @InjectMocks
    DocBlueprintUseCase useCase;

    @TempDir
    Path tmp;

    private static final byte[] DOCX_BYTES = {0x50, 0x4b, 0x03, 0x04};

    @Test
    void pdfUploadUsesTheReflowAndSavesVersionOne() throws IOException {
        stubPdfUpload();
        when(pdfReflower.toDocx(Path.of("/tmp/report.pdf")))
                .thenReturn(new PdfBlueprintReflower.Reflowed(DOCX_BYTES, 12, 3, 2, 0));

        BlueprintDto dto = useCase.create(pdf(), null, null, null);

        verify(pdfToDocx, never()).convertPdfToDocx(any());
        ArgumentCaptor<DocBlueprint> captor = ArgumentCaptor.forClass(DocBlueprint.class);
        verify(blueprints).save(captor.capture());
        assertThat(captor.getValue().getOriginalFileName()).isEqualTo("report.pdf");
        assertThat(captor.getValue().getFileName()).isEqualTo("report_20260101120000.docx");
        assertThat(captor.getValue().getCurrentVersion()).isEqualTo(1);
        ArgumentCaptor<DocBlueprintVersion> version = ArgumentCaptor.forClass(DocBlueprintVersion.class);
        verify(versions).save(version.capture());
        assertThat(version.getValue().getVersion()).isEqualTo(1);
        assertThat(version.getValue().getSectionSource()).isEqualTo(BlueprintSectionExtractor.SOURCE_AI);
        assertThat(dto.blueprintId()).isEqualTo(7);
        assertThat(dto.sectionSource()).isEqualTo("AI");
        assertThat(dto.version()).isEqualTo(1);
    }

    @Test
    void pdfUploadFallsBackToLibreOfficeWhenTheReflowFindsNoText() throws IOException {
        stubPdfUpload();
        when(pdfReflower.toDocx(Path.of("/tmp/report.pdf")))
                .thenReturn(new PdfBlueprintReflower.Reflowed(new byte[0], 0, 0, 1, 0));
        when(pdfToDocx.convertPdfToDocx(Path.of("/tmp/report.pdf"))).thenReturn(DOCX_BYTES);

        useCase.create(pdf(), null, null, null);

        verify(pdfToDocx).convertPdfToDocx(Path.of("/tmp/report.pdf"));
    }

    @Test
    void aPasswordProtectedPdfIsRefusedWithoutTryingLibreOffice() throws IOException {
        when(uploadStorage.store(any(MockMultipartFile.class))).thenReturn("report_20260101120000.pdf");
        when(uploadStorage.key("report_20260101120000.pdf")).thenReturn("uploads/report_20260101120000.pdf");
        when(uploadStorage.localCopy("report_20260101120000.pdf")).thenReturn(Path.of("/tmp/report.pdf"));
        when(pdfReflower.toDocx(Path.of("/tmp/report.pdf")))
                .thenThrow(new IllegalArgumentException("This PDF is password-protected."));

        assertThatThrownBy(() -> useCase.create(pdf(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
        verify(pdfToDocx, never()).convertPdfToDocx(any());
    }

    @Test
    void savingEditedSectionsWritesTheNextVersionInDocumentOrder() throws IOException {
        DocBlueprint blueprint = storedBlueprint(1);
        List<DocBlueprintSection> written = captureSections();
        when(ownership.requireUserId()).thenReturn(1);

        BlueprintDto dto = useCase.saveSections(4, request(1, "split scope",
                input("Scope", 2, 3, List.of("Scope", "works")),
                input("Introduction", 1, 0, null)));

        assertThat(dto.version()).isEqualTo(2);
        assertThat(dto.currentVersion()).isEqualTo(2);
        assertThat(dto.sectionSource()).isEqualTo(DocBlueprintVersion.SOURCE_MANUAL);
        assertThat(blueprint.getCurrentVersion()).isEqualTo(2);
        assertThat(written).extracting(DocBlueprintSection::getTitle).containsExactly("Introduction", "Scope");
        assertThat(written).extracting(DocBlueprintSection::getAnchorParagraphIndex).containsExactly(0, 3);
        assertThat(written).extracting(DocBlueprintSection::getVersion).containsOnly(2);
        assertThat(written.get(1).getAnchorText()).isEqualTo("2. Scope of works");
        assertThat(written.get(1).getKeywords()).isEqualTo("scope,works");
        ArgumentCaptor<DocBlueprintVersion> version = ArgumentCaptor.forClass(DocBlueprintVersion.class);
        verify(versions).save(version.capture());
        assertThat(version.getValue().getNote()).isEqualTo("split scope");
        verify(audit).record(any(AuditEventSpec.class));
    }

    @Test
    void sectionsMustStartAtRealNonEmptyParagraphsUsedOnce() throws IOException {
        storedBlueprint(1);

        assertThatThrownBy(() -> useCase.saveSections(4, request(1, null, input("Gone", 1, 99, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not start");
        assertThatThrownBy(() -> useCase.saveSections(4, request(1, null, input("Blank", 1, 2, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty paragraph");
        assertThatThrownBy(() -> useCase.saveSections(4, request(1, null,
                input("A", 1, 0, null), input("B", 1, 0, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same paragraph");
        assertThatThrownBy(() -> useCase.saveSections(4, request(1, null, input("  ", 1, 0, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        verify(versions, never()).save(any());
    }

    @Test
    void aSaveBasedOnAnOlderVersionIsRefused() {
        DocBlueprint blueprint = blueprint(3);
        when(ownership.requireBlueprint(4)).thenReturn(blueprint);

        assertThatThrownBy(() -> useCase.saveSections(4, request(2, null, input("Intro", 1, 0, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 3");
        verify(versions, never()).save(any());
    }

    @Test
    void resectionSavesTheExtractorResultAsANewVersion() throws IOException {
        DocBlueprint blueprint = blueprint(2);
        when(ownership.requireBlueprint(4)).thenReturn(blueprint);
        when(ownership.requireUserId()).thenReturn(1);
        when(uploadStorage.localCopy("bp.docx")).thenReturn(Path.of("/tmp/bp.docx"));
        DocumentCreationFlow flow = org.mockito.Mockito.mock(DocumentCreationFlow.class);
        when(flow.customizeSections(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(flows.flowFor(null)).thenReturn(flow);
        when(sectionExtractor.extract(Path.of("/tmp/bp.docx"), "Report"))
                .thenReturn(new BlueprintSectionExtractor.Extraction(List.of(
                        new ParsedSection(0, "Intro", 1, 0, "Intro", List.of("intro")),
                        new ParsedSection(1, "Scope", 1, 3, "Scope", List.of())),
                        BlueprintSectionExtractor.SOURCE_AI));
        when(blueprints.save(blueprint)).thenReturn(blueprint);
        List<DocBlueprintSection> written = captureSections();

        BlueprintDto dto = useCase.resection(4, 2, null);

        assertThat(dto.version()).isEqualTo(3);
        assertThat(dto.sectionSource()).isEqualTo("AI");
        assertThat(written).hasSize(2).extracting(DocBlueprintSection::getVersion).containsOnly(3);
    }

    @Test
    void keywordsOfAnOlderVersionAreReadOnly() {
        when(ownership.requireBlueprint(4)).thenReturn(blueprint(2));
        DocBlueprintSection old = new DocBlueprintSection();
        old.setDocBlueprintSectionId(11);
        old.setBlueprintId(4);
        old.setVersion(1);
        when(sections.getById(11)).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> useCase.updateKeywords(4, 11, List.of("roof")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("older version");
        verify(sections, never()).save(any());
    }

    @Test
    void getReadsTheRequestedVersionAndRejectsUnknownOnes() {
        when(ownership.requireBlueprint(4)).thenReturn(blueprint(2));
        when(versions.get(4, 1)).thenReturn(Optional.empty());
        when(sections.listByBlueprintVersion(4, 1)).thenReturn(List.of());

        BlueprintDto dto = useCase.get(4, 1);

        assertThat(dto.version()).isEqualTo(1);
        assertThat(dto.currentVersion()).isEqualTo(2);
        assertThatThrownBy(() -> useCase.get(4, 3)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void paragraphsListOnlyNonEmptyParagraphsWithTheirBodyIndex() throws IOException {
        storedBlueprint(1);

        assertThat(useCase.paragraphs(4))
                .extracting(p -> p.index() + ":" + p.text() + ":" + p.headingCandidate())
                .containsExactly("0:INTRODUCTION:true", "1:This report covers the site visit and its findings, "
                        + "including every observation recorded by the surveyor during the inspection.:false",
                        "3:2. Scope of works:true");
    }

    @Test
    void blueprintDownloadNameMapsPdfOriginalToDocx() {
        assertThat(DocBlueprintUseCase.blueprintDownloadName("survey.pdf")).isEqualTo("survey.docx");
        assertThat(DocBlueprintUseCase.blueprintDownloadName("survey.docx")).isEqualTo("survey.docx");
        assertThat(DocBlueprintUseCase.blueprintDownloadName("survey").toLowerCase(Locale.ROOT))
                .isEqualTo("survey.docx");
    }

    // ---------------------------------------------------------------- helpers

    private void stubPdfUpload() throws IOException {
        when(ownership.requireUserId()).thenReturn(1);
        when(ownership.requireBankId()).thenReturn(1);
        DocumentCreationFlow flow = org.mockito.Mockito.mock(DocumentCreationFlow.class);
        when(flow.flowKey()).thenReturn("DEFAULT");
        when(flow.customizeSections(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(flows.flowFor(null)).thenReturn(flow);
        Path docx = tmp.resolve("report.docx");
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(docx)) {
            doc.createParagraph().createRun().setText("Intro");
            doc.write(out);
        }
        byte[] docxBytes = Files.readAllBytes(docx);
        when(templateNormalizer.normalize(any(), any(), any()))
                .thenAnswer(inv -> new TemplateNormalizer.Result(inv.getArgument(0), 0, false));
        when(fieldExtractor.extractSectionFields(any(), any(), any())).thenReturn(null);
        when(uploadStorage.store(any(MockMultipartFile.class))).thenReturn("report_20260101120000.pdf");
        when(uploadStorage.key("report_20260101120000.pdf")).thenReturn("uploads/report_20260101120000.pdf");
        when(uploadStorage.localCopy("report_20260101120000.pdf")).thenReturn(Path.of("/tmp/report.pdf"));
        when(uploadStorage.store(eq("report.docx"), eq(DOCX_BYTES))).thenReturn("report_20260101120000.docx");
        when(uploadStorage.key("report_20260101120000.docx")).thenReturn("uploads/report_20260101120000.docx");
        when(uploadStorage.localCopy("report_20260101120000.docx")).thenReturn(docx);
        when(sectionExtractor.extract(docx, "report"))
                .thenReturn(new BlueprintSectionExtractor.Extraction(
                        List.of(new ParsedSection(0, "Intro", 1, 0, "Intro", List.of("intro"))),
                        BlueprintSectionExtractor.SOURCE_AI));
        DocBlueprint saved = new DocBlueprint();
        saved.setDocBlueprintId(7);
        saved.setBankId(1);
        saved.setFileName("report_20260101120000.docx");
        when(blueprints.save(any(DocBlueprint.class))).thenAnswer(inv -> {
            DocBlueprint bp = inv.getArgument(0);
            if (bp.getDocBlueprintId() == 0) {
                bp.setDocBlueprintId(7);
            }
            return bp;
        });
        when(sections.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[]{1, 2, 3});
    }

    private static DocBlueprint blueprint(int currentVersion) {
        DocBlueprint blueprint = new DocBlueprint();
        blueprint.setDocBlueprintId(4);
        blueprint.setBankId(1);
        blueprint.setName("Report");
        blueprint.setFileName("bp.docx");
        blueprint.setCurrentVersion(currentVersion);
        return blueprint;
    }

    /**
     * A stored blueprint whose body is: [0] INTRODUCTION, [1] a body
     * sentence, [2] an empty paragraph, [3] "2. Scope of works".
     */
    private DocBlueprint storedBlueprint(int currentVersion) throws IOException {
        Path docx = tmp.resolve("bp.docx");
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(docx)) {
            doc.createParagraph().createRun().setText("INTRODUCTION");
            doc.createParagraph().createRun().setText("This report covers the site visit and its findings, "
                    + "including every observation recorded by the surveyor during the inspection.");
            doc.createParagraph();
            doc.createParagraph().createRun().setText("2. Scope of works");
            doc.write(out);
        }
        DocBlueprint blueprint = blueprint(currentVersion);
        when(ownership.requireBlueprint(4)).thenReturn(blueprint);
        when(uploadStorage.localCopy("bp.docx")).thenReturn(docx);
        org.mockito.Mockito.lenient().when(blueprints.save(blueprint)).thenReturn(blueprint);
        return blueprint;
    }

    private List<DocBlueprintSection> captureSections() {
        List<DocBlueprintSection> written = new ArrayList<>();
        when(sections.save(any())).thenAnswer(inv -> {
            DocBlueprintSection section = inv.getArgument(0);
            written.add(section);
            return section;
        });
        return written;
    }

    private static SaveSectionsRequest request(int baseVersion, String note, SaveSectionsRequest.SectionInput... inputs) {
        SaveSectionsRequest request = new SaveSectionsRequest();
        request.setBaseVersion(baseVersion);
        request.setNote(note);
        request.setSections(List.of(inputs));
        return request;
    }

    private static SaveSectionsRequest.SectionInput input(String title, int level, int anchor, List<String> keywords) {
        SaveSectionsRequest.SectionInput input = new SaveSectionsRequest.SectionInput();
        input.setTitle(title);
        input.setHeadingLevel(level);
        input.setAnchorParagraphIndex(anchor);
        input.setKeywords(keywords);
        return input;
    }
}
