package com.modocument.infrastructure.persistence;

import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.domain.entity.TemplateFields;
import org.springframework.stereotype.Repository;

import java.util.List;
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
    public void add(TemplateFields entity) {
        jpa.save(entity);
    }

    @Override
    public TemplateFields update(TemplateFields entity) {
        return jpa.save(entity);
    }

    @Override
    public void delete(int id) {
        jpa.findById(id).ifPresent(jpa::delete);
    }
}
