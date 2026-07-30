package com.modocument.infrastructure.persistence;

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
    public List<ImportHistory> list() {
        return jpa.findAll();
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
        return jpa.save(entity);
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
    public int countByTemplateId(int templateId) {
        return (int) jpa.countByTemplateId(templateId);
    }
}
