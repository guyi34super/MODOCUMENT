package com.modocument.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.Permission;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.ValidationRequest;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.application.usecase.ValidateRowsUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@Tag(name = "Validation")
public class ValidationController {

    private final ValidateRowsUseCase useCase;
    private final TemplateOwnership ownership;

    public ValidationController(ValidateRowsUseCase useCase, TemplateOwnership ownership) {
        this.useCase = useCase;
        this.ownership = ownership;
    }

    @RequiresPermission(Permission.VALIDATION_READ)
    @Operation(summary = "Run rows through a template's validation rules")
    @PostMapping("/Validation/validate")
    public ResponseEntity<?> validate(@Valid @RequestBody ValidationRequest request) {
        // The template whose rules are being applied must belong to the caller —
        // otherwise a user could probe another template's rules through the
        // validation results.
        ownership.require(request.getTemplateId());
        return ResponseEntity.ok(useCase.handle(request));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
