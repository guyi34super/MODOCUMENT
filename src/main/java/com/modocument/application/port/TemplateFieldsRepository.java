package com.modocument.application.port;

import com.modocument.domain.entity.TemplateFields;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TemplateFieldsRepository {

    Optional<TemplateFields> getById(int id);

    List<TemplateFields> list();

    List<TemplateFields> listByTemplateId(int templateId);

    void add(TemplateFields entity);

    TemplateFields update(TemplateFields entity);

    void delete(int id);

    /**
     * ws28: the fields of several templates, keyed by template id (templates
     * without fields are absent). The JPA adapter answers with ONE query
     * instead of one per template.
     */
    default Map<Integer, List<TemplateFields>> listByTemplateIds(Collection<Integer> templateIds) {
        Map<Integer, List<TemplateFields>> byTemplate = new LinkedHashMap<>();
        for (Integer templateId : templateIds) {
            List<TemplateFields> fields = listByTemplateId(templateId);
            if (!fields.isEmpty()) {
                byTemplate.put(templateId, fields);
            }
        }
        return byTemplate;
    }

    /**
     * ws28: the field NAMES of several templates, keyed by template id — a
     * names-only projection for the job list summary.
     */
    default Map<Integer, List<String>> fieldNamesByTemplateIds(Collection<Integer> templateIds) {
        Map<Integer, List<String>> names = new LinkedHashMap<>();
        listByTemplateIds(templateIds).forEach((templateId, fields) -> {
            List<String> list = new ArrayList<>(fields.size());
            fields.forEach(field -> list.add(field.getFieldName()));
            names.put(templateId, list);
        });
        return names;
    }
}
