package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintSection;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class DocBlueprintSectionRepositoryImpl implements DocBlueprintSectionRepository {

    private final DocBlueprintSectionJpaRepository jpa;

    public DocBlueprintSectionRepositoryImpl(DocBlueprintSectionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<DocBlueprintSection> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public List<DocBlueprintSection> listByBlueprint(int blueprintId) {
        return jpa.findByBlueprintIdOrderByVersionAscOrderIndexAsc(blueprintId);
    }

    @Override
    public List<DocBlueprintSection> listByBlueprintVersion(int blueprintId, int version) {
        return jpa.findByBlueprintIdAndVersionOrderByOrderIndex(blueprintId, version);
    }

    @Override
    public DocBlueprintSection save(DocBlueprintSection entity) {
        return jpa.save(entity);
    }

    @Override
    public void deleteByBlueprint(int blueprintId) {
        jpa.deleteByBlueprintId(blueprintId);
    }

    @Override
    public java.util.Map<Integer, List<DocBlueprintSection>> listByBlueprints(
            java.util.Collection<Integer> blueprintIds) {
        java.util.Map<Integer, List<DocBlueprintSection>> byBlueprint = new java.util.LinkedHashMap<>();
        if (blueprintIds.isEmpty()) {
            return byBlueprint;
        }
        for (DocBlueprintSection section
                : jpa.findByBlueprintIdInOrderByBlueprintIdAscVersionAscOrderIndexAsc(blueprintIds)) {
            byBlueprint.computeIfAbsent(section.getBlueprintId(), id -> new java.util.ArrayList<>())
                    .add(section);
        }
        return byBlueprint;
    }

    @Override
    public java.util.Map<Integer, Long> countByBlueprintVersions(java.util.Map<Integer, Integer> versionByBlueprint) {
        java.util.Map<Integer, Long> counts = new java.util.LinkedHashMap<>();
        if (versionByBlueprint.isEmpty()) {
            return counts;
        }
        for (Object[] row : jpa.countByBlueprintIdsAndVersion(versionByBlueprint.keySet())) {
            int blueprintId = ((Number) row[0]).intValue();
            int version = ((Number) row[1]).intValue();
            if (Integer.valueOf(version).equals(versionByBlueprint.get(blueprintId))) {
                counts.put(blueprintId, ((Number) row[2]).longValue());
            }
        }
        return counts;
    }
}
