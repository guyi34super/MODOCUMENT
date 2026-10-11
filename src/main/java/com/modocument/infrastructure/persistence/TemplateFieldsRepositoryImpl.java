package com.modocument.infrastructure.persistence;

import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.domain.entity.TemplateFields;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TemplateFieldsRepositoryImpl implements TemplateFieldsRepository {

    private final TemplateFieldsJpaRepository jpa;

    public TemplateFieldsRepositoryImpl(TemplateFieldsJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<TemplateFields> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public List<TemplateFields> list() {
        return jpa.findAll();
    }

    @Override
    public List<TemplateFields> listByTemplateId(int templateId) {
        return jpa.findByTemplateId(templateId);
    }

    @Override
    public Map<Integer, List<TemplateFields>> listByTemplateIds(Collection<Integer> templateIds) {
        Map<Integer, List<TemplateFields>> byTemplate = new LinkedHashMap<>();
        if (templateIds == null || templateIds.isEmpty()) {
            return byTemplate;
        }
        for (TemplateFields field : jpa.findByTemplateIdInOrderByTemplateFieldsIdAsc(templateIds)) {
            byTemplate.computeIfAbsent(field.getTemplateId(), id -> new ArrayList<>()).add(field);
        }
        return byTemplate;
    }

    @Override
    public Map<Integer, List<String>> fieldNamesByTemplateIds(Collection<Integer> templateIds) {
        Map<Integer, List<String>> names = new LinkedHashMap<>();
        if (templateIds == null || templateIds.isEmpty()) {
            return names;
        }
        for (Object[] row : jpa.findFieldNames(templateIds)) {
            names.computeIfAbsent(((Number) row[0]).intValue(), id -> new ArrayList<>())
                    .add((String) row[1]);
        }
        return names;
    }

    @Override
    public void add(TemplateFields entity) {
        jpa.save(entity);
    }

    /**
     * The merged row re-read with its associations fetched: a merge leaves
     * them as uninitialized proxies, which would fail to serialize once the
     * transaction is gone (LAZY since ws28, open-in-view off).
     */
    @Override
    public TemplateFields update(TemplateFields entity) {
        TemplateFields saved = jpa.save(entity);
        return jpa.findById(saved.getTemplateFieldsId()).orElse(saved);
    }

    @Override
    public void delete(int id) {
        jpa.findById(id).ifPresent(jpa::delete);
    }
}
