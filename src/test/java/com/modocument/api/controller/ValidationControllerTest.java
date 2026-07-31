package com.modocument.api.controller;

import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.ValidationRequest;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.application.usecase.ValidateRowsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValidationControllerTest {

    private final ValidateRowsUseCase useCase = mock(ValidateRowsUseCase.class);
    private final TemplateOwnership ownership = mock(TemplateOwnership.class);
    private final ValidationController controller = new ValidationController(useCase, ownership);

    @Test
    void validateReturnsOkWithResults() {
        List<RowValidationResult> expected = List.of(
                new RowValidationResult(1, "FirstName", "Ada"),
                new RowValidationResult(1, "Gender", "Q", "Gender must be either M, F or U"));
        when(useCase.handle(any(ValidationRequest.class))).thenReturn(expected);

        ResponseEntity<?> result = controller.validate(new ValidationRequest(1, List.of()));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(expected);
    }
}
