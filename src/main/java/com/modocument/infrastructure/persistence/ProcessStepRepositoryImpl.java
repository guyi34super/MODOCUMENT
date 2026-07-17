package com.modocument.infrastructure.persistence;

import com.modocument.application.port.ProcessStepRepository;
import com.modocument.domain.entity.ProcessStep;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProcessStepRepositoryImpl implements ProcessStepRepository {

    private final ProcessStepJpaRepository jpa;

    public ProcessStepRepositoryImpl(ProcessStepJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<ProcessStep> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public List<ProcessStep> list() {
        return jpa.findAll();
    }

    @Override
    public void add(ProcessStep entity) {
        jpa.save(entity);
    }

    @Override
    public ProcessStep update(ProcessStep entity) {
        return jpa.save(entity);
    }

    @Override
    public void delete(int id) {
        jpa.findById(id).ifPresent(jpa::delete);
    }
}
