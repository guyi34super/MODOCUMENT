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
        return jpa.findByBlueprintIdOrderByOrderIndex(blueprintId);
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
                : jpa.findByBlueprintIdInOrderByBlueprintIdAscOrderIndexAsc(blueprintIds)) {
            byBlueprint.computeIfAbsent(section.getBlueprintId(), id -> new java.util.ArrayList<>())
                    .add(section);
        }
        return byBlueprint;
    }

    @Override
    public java.util.Map<Integer, Long> countByBlueprints(java.util.Collection<Integer> blueprintIds) {
        return blueprintIds.isEmpty() ? java.util.Map.of() : counts(jpa.countByBlueprintIds(blueprintIds));
    }

    private static java.util.Map<Integer, Long> counts(List<Object[]> rows) {
        java.util.Map<Integer, Long> counts = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }
}
