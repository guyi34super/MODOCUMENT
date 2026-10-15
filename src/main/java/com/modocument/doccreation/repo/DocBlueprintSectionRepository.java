package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintSection;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public interface DocBlueprintSectionRepository {

    Optional<DocBlueprintSection> getById(int id);

    /** Every section row of a blueprint, all versions (V291), in version then document order. */
    List<DocBlueprintSection> listByBlueprint(int blueprintId);

    /** The sections of one blueprint version in document order. */
    default List<DocBlueprintSection> listByBlueprintVersion(int blueprintId, int version) {
        return listByBlueprint(blueprintId).stream()
                .filter(s -> s.getVersion() == version)
                .sorted(Comparator.comparingInt(DocBlueprintSection::getOrderIndex))
                .toList();
    }

    DocBlueprintSection save(DocBlueprintSection entity);

    void deleteByBlueprint(int blueprintId);

    /** ws28: the sections of several blueprints (all versions) in document order, keyed by blueprint id. */
    default java.util.Map<Integer, List<DocBlueprintSection>> listByBlueprints(
            java.util.Collection<Integer> blueprintIds) {
        java.util.Map<Integer, List<DocBlueprintSection>> byBlueprint = new java.util.LinkedHashMap<>();
        for (Integer id : blueprintIds) {
            byBlueprint.put(id, listByBlueprint(id));
        }
        return byBlueprint;
    }

    /**
     * ws28: how many sections each blueprint's given version has (keyed by
     * blueprint id → version; blueprints without sections are absent).
     */
    default java.util.Map<Integer, Long> countByBlueprintVersions(java.util.Map<Integer, Integer> versionByBlueprint) {
        java.util.Map<Integer, Long> counts = new java.util.LinkedHashMap<>();
        versionByBlueprint.forEach((id, version) -> {
            long count = listByBlueprintVersion(id, version).size();
            if (count > 0) {
                counts.put(id, count);
            }
        });
        return counts;
    }
}
