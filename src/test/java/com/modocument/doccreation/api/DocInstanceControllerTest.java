package com.modocument.doccreation.api;

import com.modocument.doccreation.dto.BulkUploadResultDto;
import com.modocument.doccreation.dto.CreateDocInstanceRequest;
import com.modocument.doccreation.dto.InstanceSummaryDto;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocInstanceControllerTest {

    @TempDir
    Path tempDir;

    private final DocInstanceUseCase useCase = mock(DocInstanceUseCase.class);
    private final DocInstanceController controller = new DocInstanceController(useCase);

    @Test
    void createReturns201WithLocation() {
        CreateDocInstanceRequest request = new CreateDocInstanceRequest();
        request.setBlueprintId(1);
        request.setTitle("Survey");
        when(useCase.create(1, "Survey"))
                .thenReturn(new InstanceSummaryDto(8, "Survey", 1, "PV", "DRAFT", null, null,
                        false, null, null, null));

        ResponseEntity<?> result = controller.create(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getHeaders().getLocation()).hasToString("/api/doccreation/instances/8");
    }

    @Test
    void emptyBulkUploadAnswers400() {
        ResponseEntity<?> result = controller.uploadAssets(1, List.of(), true);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void bulkUploadReturnsTheSplitResult() {
        BulkUploadResultDto split = new BulkUploadResultDto(
                List.of(new BulkUploadResultDto.AssignedDto(1, "roof.png", 10, 100)),
                List.of(new BulkUploadResultDto.UnsortedDto(2, "img.png")),
                List.of(new BulkUploadResultDto.RejectedDto("x.webp", "Unsupported image type.")));
        when(useCase.uploadAssets(anyInt(), anyList(), anyBoolean())).thenReturn(split);

        ResponseEntity<?> result = controller.uploadAssets(1,
                List.of(new MockMultipartFile("files", "roof.png", null, new byte[]{1})), true);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(split);
    }

    @Test
    void downloadSetsAttachmentDispositionAndContentType() throws IOException {
        Path file = tempDir.resolve("doccreation-instance1.docx");
        Files.write(file, new byte[]{1, 2, 3});
        when(useCase.download(1, "docx"))
                .thenReturn(new DocInstanceUseCase.DownloadFile(file, "Survey 42.docx"));

        ResponseEntity<?> result = controller.download(1, "docx");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"Survey 42.docx\"");
        assertThat(result.getHeaders().getContentType().toString())
                .contains("officedocument.wordprocessingml.document");
    }

    @Test
    void pdfDownloadWithoutConverterAnswers409() {
        when(useCase.download(1, "pdf"))
                .thenThrow(new IllegalStateException("PDF conversion is not available on this server."));

        IllegalStateException e;
        try {
            controller.download(1, "pdf");
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException caught) {
            e = caught;
        }

        assertThat(controller.conflict(e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void assetContentServesInlineWithTheStoredContentType() throws IOException {
        Path image = tempDir.resolve("roof.png");
        Files.write(image, new byte[]{9});
        when(useCase.getAssetContent(4))
                .thenReturn(new DocInstanceUseCase.StoredAsset(image, "image/png", "roof.png"));

        ResponseEntity<?> result = controller.assetContent(4);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("inline");
        assertThat(result.getHeaders().getContentType()).hasToString("image/png");
    }
}
