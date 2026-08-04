package com.modocument.application.port;

import com.modocument.domain.entity.Template;

import java.util.List;
import java.util.Optional;

public interface TemplateRepository {

    Optional<Template> getById(int id);

    /** Owner-scoped read: empty for a template the caller does not own. */
    Optional<Template> getByIdAndOwner(int id, int ownerUserId);

    List<Template> list();

    List<Template> listByOwner(int ownerUserId);

    void add(Template entity);

    Template update(Template entity);

    void delete(int id);
}
