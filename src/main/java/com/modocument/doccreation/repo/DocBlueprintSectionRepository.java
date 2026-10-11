package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintSection;

import java.util.List;
import java.util.Optional;

public interface DocBlueprintSectionRepository {

    Optional<DocBlueprintSection> getById(int id);

    /** Sections of a blueprint in document order. */
    List<DocBlueprintSection> listByBlueprint(int blueprintId);

    DocBlueprintSection save(DocBlueprintSection entity);

    void deleteByBlueprint(int blueprintId);

    /** ws28: the sections of several blueprints in document order, keyed by blueprint id. */
    default java.util.Map<Integer, List<DocBlueprintSection>> listByBlueprints(
            java.util.Collection<Integer> blueprintIds) {
        java.util.Map<Integer, List<DocBlueprintSection>> byBlueprint = new java.util.LinkedHashMap<>();
        for (Integer id : blueprintIds) {
            byBlueprint.put(id, listByBlueprint(id));
        }
        return byBlueprint;
    }

    /** ws28: how many sections each blueprint has (blueprints without sections are absent). */
    default java.util.Map<Integer, Long> countByBlueprints(java.util.Collection<Integer> blueprintIds) {
        java.util.Map<Integer, Long> counts = new java.util.LinkedHashMap<>();
        for (Integer id : blueprintIds) {
            long count = listByBlueprint(id).size();
            if (count > 0) {
                counts.put(id, count);
            }
        }
        return counts;
    }
}
