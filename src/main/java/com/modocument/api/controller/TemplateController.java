package com.modocument.api.controller;

import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.Template;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

@RestController
public class TemplateController {

    private final TemplateRepository repo;

    public TemplateController(TemplateRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/Templates/GetAll")
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok(repo.list());
    }

    @GetMapping("/Templates/GetById/{id}")
    public ResponseEntity<?> getTemplateById(@PathVariable int id) {
        Optional<Template> item = repo.getById(id);
        if (item.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Template dto = new Template(item.get().getTemplateId(), item.get().getName(),
                item.get().getDescription());
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/Templates/Create")
    public ResponseEntity<?> create(@RequestBody Template dto) {
        Template entity = new Template(0, dto.getName(), dto.getDescription());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/Templates/GetById/" + entity.getTemplateId()))
                .body(entity);
    }

    @PutMapping("/Templates/Update")
    public ResponseEntity<?> update(@RequestBody Template dto) {
        Optional<Template> existingOpt = repo.getById(dto.getTemplateId());
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
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
        Optional<Template> existing = repo.getById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repo.delete(id);
        return ResponseEntity.noContent().build();
    }
}
