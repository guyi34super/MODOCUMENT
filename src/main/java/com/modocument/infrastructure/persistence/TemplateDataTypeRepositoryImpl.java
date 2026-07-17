package com.modocument.infrastructure.persistence;

import com.modocument.application.port.TemplateDataTypeRepository;
import com.modocument.domain.entity.TemplateDataType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TemplateDataTypeRepositoryImpl implements TemplateDataTypeRepository {

    private final TemplateDataTypeJpaRepository jpa;

    public TemplateDataTypeRepositoryImpl(TemplateDataTypeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<TemplateDataType> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public List<TemplateDataType> list() {
        return jpa.findAll();
    }

    @Override
    public void add(TemplateDataType entity) {
        jpa.save(entity);
    }

    @Override
    public TemplateDataType update(TemplateDataType entity) {
        return jpa.save(entity);
    }

    @Override
    public void delete(int id) {
        jpa.findById(id).ifPresent(jpa::delete);
    }
}
