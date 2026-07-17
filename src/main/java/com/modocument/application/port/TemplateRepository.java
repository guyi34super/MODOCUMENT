package com.modocument.application.port;

import com.modocument.domain.entity.Template;

import java.util.List;
import java.util.Optional;

public interface TemplateRepository {

    Optional<Template> getById(int id);

    List<Template> list();

    void add(Template entity);

    Template update(Template entity);

    void delete(int id);
}
