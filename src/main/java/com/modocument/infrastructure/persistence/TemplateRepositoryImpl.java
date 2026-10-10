package com.modocument.infrastructure.persistence;

import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.Template;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TemplateRepositoryImpl implements TemplateRepository {

    private final TemplateJpaRepository jpa;

    public TemplateRepositoryImpl(TemplateJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Template> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public Optional<Template> getAccessible(int id, int userId, int bankId) {
        return jpa.findAccessible(id, userId, bankId);
    }

    @Override
    public List<Template> list() {
        return jpa.findAll();
    }

    @Override
    public List<Template> listAccessible(int userId, int bankId) {
        return jpa.findAccessible(userId, bankId);
    }

    @Override
    public void add(Template entity) {
        jpa.save(entity);
    }

    @Override
    public Template update(Template entity) {
        return jpa.save(entity);
    }

    @Override
    public void delete(int id) {
        jpa.findById(id).ifPresent(jpa::delete);
    }
}
