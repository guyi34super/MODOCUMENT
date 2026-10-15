package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocTemplateRegion;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DocTemplateRegionRepositoryImpl implements DocTemplateRegionRepository {

    private final DocTemplateRegionJpaRepository jpa;

    public DocTemplateRegionRepositoryImpl(DocTemplateRegionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<DocTemplateRegion> listByBlueprintVersion(int bankId, int blueprintId, int version) {
        return jpa.findByBankIdAndBlueprintIdAndVersionOrderBySortOrderAscDocTemplateRegionIdAsc(
                bankId, blueprintId, version);
    }

    @Override
    public Map<Integer, List<DocTemplateRegion>> listByBlueprints(int bankId, Collection<Integer> blueprintIds) {
        Map<Integer, List<DocTemplateRegion>> byBlueprint = new LinkedHashMap<>();
        if (blueprintIds.isEmpty()) {
            return byBlueprint;
        }
        for (DocTemplateRegion region
                : jpa.findByBankIdAndBlueprintIdInOrderByBlueprintIdAscVersionAscSortOrderAsc(bankId, blueprintIds)) {
            byBlueprint.computeIfAbsent(region.getBlueprintId(), id -> new ArrayList<>()).add(region);
        }
        return byBlueprint;
    }

    @Override
    public Map<Integer, Long> countByVersion(int bankId, int blueprintId) {
        Map<Integer, Long> counts = new LinkedHashMap<>();
        for (Object[] row : jpa.countByVersion(bankId, blueprintId)) {
            counts.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    @Override
    public List<DocTemplateRegion> saveAll(List<DocTemplateRegion> regions) {
        return jpa.saveAll(regions);
    }

    @Override
    public void deleteByBlueprintVersion(int bankId, int blueprintId, int version) {
        jpa.deleteVersion(bankId, blueprintId, version);
    }

    @Override
    public void deleteByBlueprint(int bankId, int blueprintId) {
        jpa.deleteBlueprint(bankId, blueprintId);
    }
}
