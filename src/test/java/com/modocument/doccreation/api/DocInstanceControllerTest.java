package com.modocument.doccreation.api;

import com.modocument.doccreation.dto.CreateDocInstanceRequest;
import com.modocument.doccreation.dto.CreatedInstanceDto;
import com.modocument.doccreation.dto.ImageUploadResultDto;
import com.modocument.doccreation.usecase.DocCreationException;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocInstanceControllerTest {

    private final DocInstanceUseCase useCase = mock(DocInstanceUseCase.class);
    private final DocInstanceController controller = new DocInstanceController(useCase);

    @Test
    void createReturns201WithLocationAndBothIds() {
        CreateDocInstanceRequest request = new CreateDocInstanceRequest();
        request.setBlueprintId(1);
        request.setTitle("Survey");
        when(useCase.create(1, "Survey")).thenReturn(new CreatedInstanceDto(8, 8));

        ResponseEntity<?> result = controller.create(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getHeaders().getLocation()).hasToString("/api/doccreation/instances/8");
        assertThat(result.getBody()).isEqualTo(new CreatedInstanceDto(8, 8));
    }

    @Test
    void emptyImageUploadAnswers400() {
        assertThat(controller.uploadImages(1, List.of(), true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.uploadImages(1, null, true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void imageUploadReturnsTheSplitResultAs201() {
        ImageUploadResultDto split = new ImageUploadResultDto(
                List.of(new ImageUploadResultDto.AssignedDto(1, "photo_1")), List.of(2),
                List.of(new ImageUploadResultDto.RejectedDto("a.txt", "Not a readable PNG or JPEG image.")));
        when(useCase.uploadImages(org.mockito.ArgumentMatchers.eq(4), anyList(),
                org.mockito.ArgumentMatchers.eq(true))).thenReturn(split);
        List<MultipartFile> files = List.of(new MockMultipartFile("files", "a.png", "image/png", new byte[]{1}));

        ResponseEntity<?> result = controller.uploadImages(4, files, true);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(split);
    }

    @Test
    void downloadAndImageContentCarryTheirTypes() {
        when(useCase.download(5)).thenReturn(new DocInstanceUseCase.PdfDownload(new byte[]{1}, "Survey.pdf"));
        when(useCase.imageContent(5, 9)).thenReturn(
                new DocInstanceUseCase.StoredImage(new byte[]{2}, "image/jpeg", "a.jpg"));

        ResponseEntity<byte[]> pdf = controller.download(5, "docx");
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(pdf.getHeaders().getFirst("Content-Disposition")).contains("Survey.pdf");
        assertThat(controller.imageContent(5, 9).getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @Test
    void exactnessFailureAnswers500WithItsCode() {
        ResponseEntity<?> result = controller.docCreation(new DocCreationException(500,
                DocCreationException.EXACTNESS_FAILED, "refused"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(((Map<?, ?>) result.getBody()).get("code")).isEqualTo("EXACTNESS_FAILED");
    }
}
