package com.modocument.api.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Permission;
import com.modocument.application.dto.TemplateDto;
import com.modocument.application.dto.TemplateVisibilityRequest;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.usecase.DeleteTemplateUseCase;
import com.modocument.application.usecase.PendingChangeUseCase;
import com.modocument.application.usecase.TemplateVisibilityUseCase;
import com.modocument.application.usecase.UpdateTemplateUseCase;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.ExtractionMode;
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

/**
 * Template CRUD. Update and delete are maker/checker-gated (ws10, SEC-023):
 * with {@code modocument.security.maker-checker.template-changes} on (the
 * production default) they SUBMIT a PendingChange (202) for a different
 * user's approval; a single-user dev setup can turn the gate off, and the
 * same use cases then apply directly. User-security changes have no such
 * switch — only template changes are configurable.
 *
 * <p>ws22 bank-shared templates (docs/phase2/template-visibility.md): reads
 * list and return the caller's own templates plus those shared with their
 * bank ({@link TemplateDto} adds visibility, ownedByMe, editable,
 * ownerName); changes follow TemplateOwnership.requireWritable (owner for
 * PRIVATE, template:publish for BANK); sharing itself is
 * {@code PUT /Templates/{id}/Visibility} (template:publish, direct, audited
 * TEMPLATE_VISIBILITY_CHANGED).
 */
@RestController
@Tag(name = "Templates", description = "Template CRUD (maker-checker gated)")
public class TemplateController {

    private final TemplateRepository repo;
    private final TemplateVisibilityUseCase templateViews;
    private final UpdateTemplateUseCase updateTemplate;
    private final DeleteTemplateUseCase deleteTemplate;
    private final PendingChangeUseCase pendingChanges;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;
    private final boolean makerCheckerForTemplates;

    public TemplateController(TemplateRepository repo, TemplateVisibilityUseCase templateViews,
                              UpdateTemplateUseCase updateTemplate,
                              DeleteTemplateUseCase deleteTemplate,
                              PendingChangeUseCase pendingChanges,
                              CurrentUserProvider currentUser, ObjectMapper objectMapper,
                              @Value("${modocument.security.maker-checker.template-changes:true}")
                              boolean makerCheckerForTemplates) {
        this.repo = repo;
        this.templateViews = templateViews;
        this.updateTemplate = updateTemplate;
        this.deleteTemplate = deleteTemplate;
        this.pendingChanges = pendingChanges;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.makerCheckerForTemplates = makerCheckerForTemplates;
    }

    /** Own templates plus those shared with the caller's bank (ws22). */
    @RequiresPermission(Permission.TEMPLATE_READ)
    @GetMapping("/Templates/GetAll")
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok(templateViews.list());
    }

    @RequiresPermission(Permission.TEMPLATE_READ)
    @GetMapping("/Templates/GetById/{id}")
    public ResponseEntity<?> getTemplateById(@PathVariable int id) {
        try {
            return ResponseEntity.ok(templateViews.get(id));
        } catch (NoSuchElementException e) {
            return notFound(id);
        }
    }

    /**
     * Share a template with the caller's bank ({@code BANK}) or make it its
     * owner's alone again ({@code PRIVATE}); template:publish (bank ADMIN)
     * only. Direct (no maker/checker: sharing changes who may USE the
     * template, not what it extracts) and audited TEMPLATE_VISIBILITY_CHANGED.
     * 404 for a template the caller cannot read, 409 when unsharing a
     * template colleagues still have unfinished uploads on.
     */
    @RequiresPermission(Permission.TEMPLATE_PUBLISH)
    @PutMapping("/Templates/{id}/Visibility")
    public ResponseEntity<TemplateDto> setVisibility(
            @PathVariable int id, @Valid @RequestBody TemplateVisibilityRequest request) {
        return ResponseEntity.ok(templateViews.changeVisibility(id, request.parsed()));
    }

    /**
     * ws23: {@code extractionMode} — AI_WITH_CODE_FALLBACK, CODE_ONLY,
     * AI_ONLY, or null/absent to inherit the bank's default (400 otherwise).
     */
    @RequiresPermission(Permission.TEMPLATE_WRITE)
    @PostMapping("/Templates/Create")
    public ResponseEntity<?> create(@Valid @RequestBody Template dto) {
        ExtractionMode mode = ExtractionMode.parse(dto.getExtractionMode());
        Template entity = new Template(0, dto.getName(), dto.getDescription());
        entity.setExtractionMode(mode == null ? null : mode.name());
        entity.setOwnerUserId(currentUser.requireUserId());
        entity.setBankId(currentUser.requireBankId());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/Templates/GetById/" + entity.getTemplateId()))
                .body(entity);
    }

    /**
     * Gated: 202 + the pending change; config-off: applies and answers like
     * before. ws23: a body naming {@code extractionMode} sets it (null =
     * inherit the bank default); a body without it keeps the stored mode.
     */
    @RequiresPermission(Permission.TEMPLATE_WRITE)
    @PutMapping("/Templates/Update")
    public ResponseEntity<?> update(@Valid @RequestBody Template dto) {
        UpdateTemplateUseCase.ExtractionModeChange modeChange = dto.isExtractionModeProvided()
                ? new UpdateTemplateUseCase.ExtractionModeChange(
                        ExtractionMode.parse(dto.getExtractionMode()))
                : null;
        if (makerCheckerForTemplates) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("templateId", dto.getTemplateId());
            payload.put("name", dto.getName());
            if (dto.getDescription() != null) {
                payload.put("description", dto.getDescription());
            }
            if (modeChange != null) {
                if (modeChange.mode() == null) {
                    payload.putNull("extractionMode");
                } else {
                    payload.put("extractionMode", modeChange.mode().name());
                }
            }
            // Ownership is checked at submit: a foreign template 404s here,
            // exactly like the direct path — no probing via the queue.
            return ResponseEntity.accepted()
                    .body(pendingChanges.submit(ChangeType.TEMPLATE_UPDATE, payload, null));
        }
        Template response;
        try {
            // Owner-scoped: updating another user's template is a 404, not an edit.
            response = modeChange == null
                    ? updateTemplate.handle(dto.getTemplateId(), dto.getName(),
                            dto.getDescription(), currentUser.requireUserId())
                    : updateTemplate.handle(dto.getTemplateId(), dto.getName(),
                            dto.getDescription(), modeChange, currentUser.requireUserId());
        } catch (NoSuchElementException e) {
            return notFound(dto.getTemplateId());
        }
        Template updated = view(response);

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

    /** The API view of a template (no owner/bank columns). */
    private static Template view(Template template) {
        Template view = new Template(template.getTemplateId(), template.getName(),
                template.getDescription());
        view.setExtractionMode(template.getExtractionMode());
        return view;
    }

    /** A body the frontend can render; an empty 404 degrades to "Request failed (404)". */
    private static ResponseEntity<?> notFound(int id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Template " + id + " not found."));
    }
}
