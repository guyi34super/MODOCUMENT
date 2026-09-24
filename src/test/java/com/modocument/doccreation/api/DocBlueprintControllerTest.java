package com.modocument.doccreation.api;

import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.usecase.DocBlueprintUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocBlueprintControllerTest {

    private final DocBlueprintUseCase useCase = mock(DocBlueprintUseCase.class);
    private final DocBlueprintController controller = new DocBlueprintController(useCase);

    @Test
    void createReturns201WithLocation() {
        MockMultipartFile file = new MockMultipartFile("file", "report.docx", null, new byte[]{1});
        when(useCase.create(any(), isNull(), isNull()))
                .thenReturn(new BlueprintDto(5, "report", null, "DEFAULT", "report.docx", null,
                        List.of(), "AI"));

        ResponseEntity<?> result = controller.create(file, null, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getHeaders().getLocation())
                .hasToString("/api/doccreation/blueprints/5");
    }

    @Test
    void aNonDocxUploadAnswers400() {
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", null, new byte[]{1});
        when(useCase.create(any(), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException("Only .docx blueprints are supported."));

        ResponseEntity<?> result = controller.badRequest(
                catchIllegalArgument(() -> controller.create(file, null, null)));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aForeignBlueprintAnswers404() {
        when(useCase.get(9)).thenThrow(new NoSuchElementException("Blueprint 9 not found."));

        ResponseEntity<?> result = controller.notFound(
                catchNoSuchElement(() -> controller.get(9)));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteWithLiveInstancesAnswers409() {
        doThrow(new IllegalStateException("This blueprint still has documents built on it."))
                .when(useCase).delete(3);

        ResponseEntity<?> result = controller.conflict(
                catchIllegalState(() -> controller.delete(3)));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private static IllegalArgumentException catchIllegalArgument(Runnable call) {
        try {
            call.run();
        } catch (IllegalArgumentException e) {
            return e;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static NoSuchElementException catchNoSuchElement(Runnable call) {
        try {
            call.run();
        } catch (NoSuchElementException e) {
            return e;
        }
        throw new AssertionError("expected NoSuchElementException");
    }

    private static IllegalStateException catchIllegalState(Runnable call) {
        try {
            call.run();
        } catch (IllegalStateException e) {
            return e;
        }
        throw new AssertionError("expected IllegalStateException");
    }
}
