package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.Permission;
import com.modocument.application.port.ProcessStepRepository;
import com.modocument.domain.entity.ProcessStep;
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
public class ProcessStepController {

    private final ProcessStepRepository repo;

    public ProcessStepController(ProcessStepRepository repo) {
        this.repo = repo;
    }

    @RequiresPermission(Permission.LOOKUP_READ)
    @GetMapping("/ProcessStep/GetAll")
    public ResponseEntity<?> getAll() {
        return ResponseEntity.ok(repo.list());
    }

    @RequiresPermission(Permission.LOOKUP_READ)
    @GetMapping("/ProcessStep/GetById/{id}")
    public ResponseEntity<?> getProcessStepById(@PathVariable int id) {
        Optional<ProcessStep> item = repo.getById(id);
        if (item.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ProcessStep dto = new ProcessStep(item.get().getProcessStepId(), item.get().getName(),
                item.get().getDescription());
        return ResponseEntity.ok(dto);
    }

    @RequiresPermission(Permission.LOOKUP_ADMIN)
    @PostMapping("/ProcessStep/Create")
    public ResponseEntity<?> create(@RequestBody ProcessStep dto) {
        ProcessStep entity = new ProcessStep(0, dto.getName(), dto.getDescription());
        repo.add(entity);
        return ResponseEntity
                .created(URI.create("/ProcessStep/GetById/" + entity.getProcessStepId()))
                .body(entity);
    }

    @RequiresPermission(Permission.LOOKUP_ADMIN)
    @PutMapping("/ProcessStep/Update")
    public ResponseEntity<?> update(@RequestBody ProcessStep dto) {
        Optional<ProcessStep> existingOpt = repo.getById(dto.getProcessStepId());
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ProcessStep existing = existingOpt.get();
        existing.setName(dto.getName());
        existing.setDescription(dto.getDescription());

        ProcessStep response = repo.update(existing);
        ProcessStep updated = new ProcessStep(response.getProcessStepId(), response.getName(),
                response.getDescription());

        return ResponseEntity
                .created(URI.create("/ProcessStep/GetById/" + updated.getProcessStepId()))
                .body(updated);
    }

    @RequiresPermission(Permission.LOOKUP_ADMIN)
    @DeleteMapping("/ProcessStep/{id}")
    public ResponseEntity<?> delete(@PathVariable int id) {
        Optional<ProcessStep> existing = repo.getById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repo.delete(id);
        return ResponseEntity.noContent().build();
    }
}
