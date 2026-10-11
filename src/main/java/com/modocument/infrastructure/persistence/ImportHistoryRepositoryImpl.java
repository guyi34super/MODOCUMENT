package com.modocument.infrastructure.persistence;

import com.modocument.application.dto.ImportSummary;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.Template;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ImportHistoryRepositoryImpl implements ImportHistoryRepository {

    private final ImportHistoryJpaRepository jpa;
    private final TemplateJpaRepository templateJpa;

    public ImportHistoryRepositoryImpl(ImportHistoryJpaRepository jpa,
                                       TemplateJpaRepository templateJpa) {
        this.jpa = jpa;
        this.templateJpa = templateJpa;
    }

    @Override
    public Optional<ImportHistory> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public Optional<ImportHistory> getByIdAndOwner(int id, int ownerUserId) {
        return jpa.findByImportHistoryIdAndOwnerUserId(id, ownerUserId);
    }

    @Override
    public List<ImportHistory> list() {
        return jpa.findAll();
    }

    @Override
    public List<ImportHistory> listByOwner(int ownerUserId) {
        return jpa.findByOwnerUserId(ownerUserId);
    }

    @Override
    public ImportHistory add(ImportHistory entity) {
        // The referenced template must exist; mirrors the original behaviour of
        // returning an empty entity instead of persisting a dangling FK.
        Optional<Template> template = templateJpa.findById(entity.getTemplateId());
        if (template.isEmpty()) {
            return new ImportHistory();
        }
        entity.setTemplate(template.get());
        return jpa.save(entity);
    }

    @Override
    public ImportHistory update(ImportHistory entity) {
        Optional<Template> template = templateJpa.findById(entity.getTemplateId());
        if (template.isEmpty()) {
            return new ImportHistory();
        }
        entity.setTemplate(template.get());
        ImportHistory saved = jpa.save(entity);
        // ws28: a merge leaves the LAZY template an uninitialized proxy; the
        // re-read fetches it, so the row serializes outside the session.
        return jpa.findById(saved.getImportHistoryId()).orElse(saved);
    }

    @Override
    public void delete(int id) {
        jpa.findById(id).ifPresent(jpa::delete);
    }

    @Override
    public Optional<ImportHistory> getByFileName(String fileName) {
        return jpa.findFirstByFileNameIgnoreCase(fileName);
    }

    @Override
    public Optional<ImportHistory> getByFileNameAndOwner(String fileName, int ownerUserId) {
        return jpa.findFirstByFileNameIgnoreCaseAndOwnerUserId(fileName, ownerUserId);
    }

    @Override
    public int countByTemplateId(int templateId) {
        return (int) jpa.countByTemplateId(templateId);
    }

    @Override
    public long countByOwner(int ownerUserId) {
        return jpa.countByOwnerUserId(ownerUserId);
    }

    @Override
    public List<ImportSummary> listRecentSummariesByOwner(int ownerUserId, int limit) {
        return jpa.findRecentSummaries(ownerUserId, org.springframework.data.domain.PageRequest.of(0, limit));
    }
}
