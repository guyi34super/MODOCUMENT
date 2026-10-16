package com.modocument.doccreation.api;

import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.dto.SaveOverlayRequest;
import com.modocument.doccreation.usecase.DocBlueprintUseCase;
import com.modocument.doccreation.usecase.DocCreationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocBlueprintControllerTest {

    private final DocBlueprintUseCase useCase = mock(DocBlueprintUseCase.class);
    private final DocBlueprintController controller = new DocBlueprintController(useCase);

    private static BlueprintDto dto(int id, String status, int version) {
        return new BlueprintDto(id, "report", status, "BLANK", version, version, List.of(), List.of(), List.of(),
                List.of(), "CODE", 0, null, "report.pdf", null, List.of());
    }

    @Test
    void createReturns201WithLocation() {
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", null, new byte[]{1});
        when(useCase.create(any(), isNull(), isNull(), eq("AUTO"))).thenReturn(dto(5, "DRAFT", 1));

        ResponseEntity<?> result = controller.create(file, null, null, "AUTO");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getHeaders().getLocation()).hasToString("/api/doccreation/blueprints/5");
    }

    @Test
    void aRefusedPdfAnswersWithItsStatusAndCode() {
        ResponseEntity<?> result = controller.docCreation(
                DocCreationException.badPdf("ENCRYPTED", "The PDF is password-protected."));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) result.getBody()).get("code")).isEqualTo("ENCRYPTED");
    }

    @Test
    void aLegacyTemplateAnswers410() {
        ResponseEntity<?> result = controller.docCreation(DocCreationException.legacy());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(((Map<?, ?>) result.getBody()).get("code")).isEqualTo("LEGACY_TEMPLATE");
    }

    @Test
    void aForeignBlueprintAnswers404() {
        ResponseEntity<?> result = controller.notFound(new NoSuchElementException("Blueprint 9 not found."));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aStaleOverlaySaveAnswers409() {
        SaveOverlayRequest request = new SaveOverlayRequest(1, List.of(), List.of(), null, true);
        when(useCase.saveOverlay(4, request)).thenThrow(new IllegalStateException("changed meanwhile"));

        IllegalStateException thrown = null;
        try {
            controller.saveOverlay(4, request);
        } catch (IllegalStateException e) {
            thrown = e;
        }

        assertThat(controller.conflict(thrown).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void pagesArePngAndFilesArePdf() {
        when(useCase.pagePng(3, 0, null, null, null)).thenReturn(new byte[]{1, 2});
        when(useCase.file(3, "original", null)).thenReturn(new DocBlueprintUseCase.PdfFile(new byte[]{3}, "a.pdf"));

        assertThat(controller.page(3, 0, null, null, null).getHeaders().getContentType())
                .isEqualTo(MediaType.IMAGE_PNG);
        ResponseEntity<byte[]> file = controller.file(3, "original", null);
        assertThat(file.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(file.getHeaders().getFirst("Content-Disposition")).contains("a.pdf");
    }

    @Test
    void deleteWithLiveInstancesAnswers409() {
        doThrow(new IllegalStateException("documents")).when(useCase).delete(3);

        IllegalStateException thrown = null;
        try {
            controller.delete(3);
        } catch (IllegalStateException e) {
            thrown = e;
        }

        assertThat(controller.conflict(thrown).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
