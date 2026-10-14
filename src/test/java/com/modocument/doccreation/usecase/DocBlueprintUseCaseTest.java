package com.modocument.doccreation.usecase;

import com.modocument.application.port.FileStore;
import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.flow.DefaultDocumentCreationFlow;
import com.modocument.doccreation.flow.DocumentCreationFlowRegistry;
import com.modocument.doccreation.parse.BlueprintSectionExtractor;
import com.modocument.doccreation.parse.ParsedSection;
import com.modocument.doccreation.pdf.LibreOfficeDocxConverter;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocBlueprintSectionRepository;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.infrastructure.file.UploadStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocBlueprintUseCaseTest {

    @Mock
    DocBlueprintRepository blueprints;
    @Mock
    DocBlueprintSectionRepository sections;
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
    LibreOfficeDocxConverter pdfToDocx;

    @InjectMocks
    DocBlueprintUseCase useCase;

    @Test
    void pdfUploadConvertsToDocxBeforeSectionExtraction() throws IOException {
        byte[] docxBytes = new byte[]{0x50, 0x4b, 0x03, 0x04};
        when(ownership.requireUserId()).thenReturn(1);
        when(ownership.requireBankId()).thenReturn(1);
        when(flows.flowFor(null)).thenReturn(new DefaultDocumentCreationFlow());
        when(uploadStorage.store(any(MockMultipartFile.class))).thenReturn("report_20260101120000.pdf");
        when(uploadStorage.key("report_20260101120000.pdf")).thenReturn("uploads/report_20260101120000.pdf");
        when(uploadStorage.localCopy("report_20260101120000.pdf")).thenReturn(Path.of("/tmp/report.pdf"));
        when(pdfToDocx.convertPdfToDocx(Path.of("/tmp/report.pdf"))).thenReturn(docxBytes);
        when(uploadStorage.store(eq("report.docx"), eq(docxBytes))).thenReturn("report_20260101120000.docx");
        when(uploadStorage.key("report_20260101120000.docx")).thenReturn("uploads/report_20260101120000.docx");
        when(uploadStorage.localCopy("report_20260101120000.docx")).thenReturn(Path.of("/tmp/report.docx"));
        when(sectionExtractor.extract(Path.of("/tmp/report.docx"), "report"))
                .thenReturn(new BlueprintSectionExtractor.Extraction(
                        List.of(new ParsedSection(0, "Intro", 1, 0, "Intro", List.of("intro"))),
                        BlueprintSectionExtractor.SOURCE_HEURISTIC));
        DocBlueprint saved = new DocBlueprint();
        saved.setDocBlueprintId(7);
        saved.setBankId(1);
        when(blueprints.save(any(DocBlueprint.class))).thenReturn(saved);
        when(sections.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile pdf = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[]{1, 2, 3});
        BlueprintDto dto = useCase.create(pdf, null, null);

        verify(pdfToDocx).convertPdfToDocx(Path.of("/tmp/report.pdf"));
        ArgumentCaptor<DocBlueprint> captor = ArgumentCaptor.forClass(DocBlueprint.class);
        verify(blueprints).save(captor.capture());
        assertThat(captor.getValue().getOriginalFileName()).isEqualTo("report.pdf");
        assertThat(captor.getValue().getFileName()).isEqualTo("report_20260101120000.docx");
        assertThat(dto.blueprintId()).isEqualTo(7);
    }

    @Test
    void blueprintDownloadNameMapsPdfOriginalToDocx() {
        assertThat(DocBlueprintUseCase.blueprintDownloadName("survey.pdf")).isEqualTo("survey.docx");
        assertThat(DocBlueprintUseCase.blueprintDownloadName("survey.docx")).isEqualTo("survey.docx");
        assertThat(DocBlueprintUseCase.blueprintDownloadName("survey").toLowerCase(Locale.ROOT))
                .isEqualTo("survey.docx");
    }
}
