package com.modocument.application.port;

import com.modocument.domain.entity.Template;

import java.util.List;
import java.util.Optional;

/**
 * Templates (ws22: owner-private or bank-shared). There is deliberately no
 * owner-only accessor any more: every caller-facing lookup goes through the
 * visibility predicate ({@link #getAccessible}/{@link #listAccessible}),
 * and whether the caller may CHANGE what it found is decided by
 * {@code TemplateOwnership.requireWritable} — see
 * docs/phase2/template-visibility.md for the classification of every call
 * site.
 */
public interface TemplateRepository {

    /** Unscoped: system paths only (a job's own template, FK resolution). */
    Optional<Template> getById(int id);

    /**
     * The caller-facing read: the template when {@code userId} owns it, or
     * when it is BANK-shared within {@code bankId}; empty otherwise (another
     * user's private template, another bank's template, a missing id —
     * indistinguishable on purpose).
     */
    Optional<Template> getAccessible(int id, int userId, int bankId);

    List<Template> list();

    /** Every template {@link #getAccessible} would return: own + bank-shared, oldest first. */
    List<Template> listAccessible(int userId, int bankId);

    void add(Template entity);

    Template update(Template entity);

    void delete(int id);

    /** ws28 (dashboard): how many templates {@link #listAccessible} would return. */
    default long countAccessible(int userId, int bankId) {
        return listAccessible(userId, bankId).size();
    }
}
