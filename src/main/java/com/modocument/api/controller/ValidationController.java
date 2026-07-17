package com.modocument.api.controller;

import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.ValidationRequest;
import com.modocument.application.usecase.ValidateRowsUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ValidationController {

    private final ValidateRowsUseCase useCase;

    public ValidationController(ValidateRowsUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/Validation/validate")
    public ResponseEntity<List<RowValidationResult>> validate(@RequestBody ValidationRequest request) {
        return ResponseEntity.ok(useCase.handle(request));
    }
}
