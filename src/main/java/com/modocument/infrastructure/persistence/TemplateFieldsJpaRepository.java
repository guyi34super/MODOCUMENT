package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.TemplateFields;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ws28: TemplateFields' {@code template} and {@code templateDataType}
 * associations are LAZY; every finder that hands entities to callers fetches
 * both in the same statement (an entity graph = a join), so field lists cost
 * one query instead of one plus a select per distinct template and data type,
 * and the entities serialize outside a session (open-in-view is off).
 */
public interface TemplateFieldsJpaRepository extends JpaRepository<TemplateFields, Integer> {

    @EntityGraph(attributePaths = {"template", "templateDataType"})
    List<TemplateFields> findByTemplateId(int templateId);

    /** ws28: the fields of several templates in one statement, in id order. */
    @EntityGraph(attributePaths = {"template", "templateDataType"})
    List<TemplateFields> findByTemplateIdInOrderByTemplateFieldsIdAsc(Collection<Integer> templateIds);

    @Override
    @EntityGraph(attributePaths = {"template", "templateDataType"})
    Optional<TemplateFields> findById(Integer id);

    @Override
    @EntityGraph(attributePaths = {"template", "templateDataType"})
    List<TemplateFields> findAll();

    /** ws28: rows of [TemplateId, FieldName] of several templates — names only, in id order. */
    @Query("select f.templateId, f.fieldName from TemplateFields f where f.templateId in :templateIds"
            + " order by f.templateFieldsId")
    List<Object[]> findFieldNames(@Param("templateIds") Collection<Integer> templateIds);
}
