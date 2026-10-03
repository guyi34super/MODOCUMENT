package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.Permission;
import com.modocument.application.dto.CreateTemplateRequest;
import com.modocument.application.dto.CreateTemplateResponse;
import com.modocument.application.usecase.CreateTemplateWithFieldsUseCase;
import com.modocument.application.validation.TemplateDefinitionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * Composite template creation: template + fields + validation rules (with
 * UI-supplied regex patterns) in one call. Kept out of TemplateController so
 * the legacy CRUD contract stays untouched.
 */
@RestController
@RequiresPermission(Permission.TEMPLATE_WRITE)
public class TemplateCompositeController {

    private final CreateTemplateWithFieldsUseCase useCase;

    public TemplateCompositeController(CreateTemplateWithFieldsUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/Templates/CreateWithFields")
    public ResponseEntity<?> createWithFields(@RequestBody CreateTemplateRequest request) {
        try {
            CreateTemplateResponse response = useCase.handle(request);
            return ResponseEntity
                    .created(URI.create("/Templates/GetById/" + response.templateId()))
                    .body(response);
        } catch (TemplateDefinitionException e) {
            return ResponseEntity.badRequest().body(Map.of("errors", e.getErrors()));
        }
    }
}
