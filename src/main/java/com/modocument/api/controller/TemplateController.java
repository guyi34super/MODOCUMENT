package com.modocument.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Permission;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.usecase.DeleteTemplateUseCase;
import com.modocument.application.usecase.PendingChangeUseCase;
import com.modocument.application.usecase.UpdateTemplateUseCase;
import com.modocument.domain.entity.Template;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Template CRUD. Update and delete are maker/checker-gated (ws10, SEC-023):
 * with {@code modocument.security.maker-checker.template-changes} on (the
 * production default) they SUBMIT a PendingChange (202) for a different
 * user's approval; a single-user dev setup can turn the gate off, and the
 * same use cases then apply directly. User-security changes have no such
 * switch — only template changes are configurable.
 */
@RestController
public class TemplateController {

    private final TemplateRepository repo;
    private final UpdateTemplateUseCase updateTemplate;
    private final DeleteTemplateUseCase deleteTemplate;
    private final PendingChangeUseCase pendingChanges;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;
    private final boolean makerCheckerForTemplates;

    public TemplateController(TemplateRepository repo, UpdateTemplateUseCase updateTemplate,
                              DeleteTemplateUseCase deleteTemplate,
                              PendingChangeUseCase pendingChanges,
                              CurrentUserProvider currentUser, ObjectMapper objectMapper,
                              @Value("${modocument.security.maker-checker.template-changes:true}")
                              boolean makerCheckerForTemplates) {
        this.repo = repo;
        this.updateTemplate = updateTemplate;
        this.deleteTemplate = deleteTemplate;
        this.pendingChanges = pendingChanges;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.makerCheckerForTemplates = makerCheckerForTemplates;
    }

    @RequiresPermission(Permission.TEMPLATE_READ)
    @GetMapping("/Templates/GetAll")
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok(repo.listByOwner(currentUser.requireUserId()));
    }

    @RequiresPermission(Permission.TEMPLATE_READ)
    @GetMapping("/Templates/GetById/{id}")
    public ResponseEntity<?> getTemplateById(@PathVariable int id) {
        Optional<Template> item = repo.getByIdAndOwner(id, currentUser.requireUserId());
        if (item.isEmpty()) {
            return notFound(id);
        }
        Template dto = new Template(item.get().getTemplateId(), item.get().getName(),
                item.get().getDescription());
        return ResponseEntity.ok(dto);
    }

    @RequiresPermission(Permission.TEMPLATE_WRITE)
    @PostMapping("/Templates/Create")
    public ResponseEntity<?> create(@Valid @RequestBody Template dto) {
        Template entity = new Template(0, dto.getName(), dto.getDescription());
        entity.setOwnerUserId(currentUser.requireUserId());
        entity.setBankId(currentUser.requireBankId());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/Templates/GetById/" + entity.getTemplateId()))
                .body(entity);
    }

    /** Gated: 202 + the pending change; config-off: applies and answers like before. */
    @RequiresPermission(Permission.TEMPLATE_WRITE)
    @PutMapping("/Templates/Update")
    public ResponseEntity<?> update(@Valid @RequestBody Template dto) {
        if (makerCheckerForTemplates) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("templateId", dto.getTemplateId());
            payload.put("name", dto.getName());
            if (dto.getDescription() != null) {
                payload.put("description", dto.getDescription());
            }
            // Ownership is checked at submit: a foreign template 404s here,
            // exactly like the direct path — no probing via the queue.
            return ResponseEntity.accepted()
                    .body(pendingChanges.submit(ChangeType.TEMPLATE_UPDATE, payload, null));
        }
        Template response;
        try {
            // Owner-scoped: updating another user's template is a 404, not an edit.
            response = updateTemplate.handle(dto.getTemplateId(), dto.getName(),
                    dto.getDescription(), currentUser.requireUserId());
        } catch (NoSuchElementException e) {
            return notFound(dto.getTemplateId());
        }
        Template updated = new Template(response.getTemplateId(), response.getName(),
                response.getDescription());

        // The original endpoint answers 201 CreatedAtAction on update; kept for parity.
        return ResponseEntity
                .created(URI.create("/Templates/GetById/" + updated.getTemplateId()))
                .body(updated);
    }

    /** Gated: 202 + the pending change; config-off: deletes and answers 204 like before. */
    @RequiresPermission(Permission.TEMPLATE_WRITE)
    @DeleteMapping("/Templates/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        if (makerCheckerForTemplates) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("templateId", id);
            return ResponseEntity.accepted()
                    .body(pendingChanges.submit(ChangeType.TEMPLATE_DELETE, payload, null));
        }
        try {
            deleteTemplate.handle(id, currentUser.requireUserId());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // Something still references the template; without this the
            // foreign key turns the delete into an unexplained 500.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    /** A body the frontend can render; an empty 404 degrades to "Request failed (404)". */
    private static ResponseEntity<?> notFound(int id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Template " + id + " not found."));
    }
}
