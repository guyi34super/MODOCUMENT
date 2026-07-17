package com.modocument.application.port;

import com.modocument.domain.entity.TemplateFields;

import java.util.List;
import java.util.Optional;

public interface TemplateFieldsRepository {

    Optional<TemplateFields> getById(int id);

    List<TemplateFields> list();

    void add(TemplateFields entity);

    TemplateFields update(TemplateFields entity);

    void delete(int id);
}
