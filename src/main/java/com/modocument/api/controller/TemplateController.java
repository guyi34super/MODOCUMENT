package com.modocument.api.controller;

import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.usecase.DeleteTemplateUseCase;
import com.modocument.domain.entity.Template;
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

@RestController
public class TemplateController {

    private final TemplateRepository repo;
    private final DeleteTemplateUseCase deleteTemplate;
    private final CurrentUserProvider currentUser;

    public TemplateController(TemplateRepository repo, DeleteTemplateUseCase deleteTemplate,
                              CurrentUserProvider currentUser) {
        this.repo = repo;
        this.deleteTemplate = deleteTemplate;
        this.currentUser = currentUser;
    }

    @GetMapping("/Templates/GetAll")
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok(repo.listByOwner(currentUser.requireUserId()));
    }

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

    @PostMapping("/Templates/Create")
    public ResponseEntity<?> create(@RequestBody Template dto) {
        Template entity = new Template(0, dto.getName(), dto.getDescription());
        entity.setOwnerUserId(currentUser.requireUserId());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/Templates/GetById/" + entity.getTemplateId()))
                .body(entity);
    }

    @PutMapping("/Templates/Update")
    public ResponseEntity<?> update(@RequestBody Template dto) {
        // Owner-scoped: updating another user's template is a 404, not an edit.
        Optional<Template> existingOpt =
                repo.getByIdAndOwner(dto.getTemplateId(), currentUser.requireUserId());
        if (existingOpt.isEmpty()) {
            return notFound(dto.getTemplateId());
        }
        Template existing = existingOpt.get();
        existing.setName(dto.getName());
        existing.setDescription(dto.getDescription());

        Template response = repo.update(existing);
        Template updated = new Template(response.getTemplateId(), response.getName(),
                response.getDescription());

        // The original endpoint answers 201 CreatedAtAction on update; kept for parity.
        return ResponseEntity
                .created(URI.create("/Templates/GetById/" + updated.getTemplateId()))
                .body(updated);
    }

    @DeleteMapping("/Templates/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
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
