package com.modocument.application.port;

import com.modocument.domain.entity.ProcessStep;

import java.util.List;
import java.util.Optional;

public interface ProcessStepRepository {

    Optional<ProcessStep> getById(int id);

    List<ProcessStep> list();

    void add(ProcessStep entity);

    ProcessStep update(ProcessStep entity);

    void delete(int id);
}
