package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocTemplateEdit;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DocTemplateEditRepositoryImpl implements DocTemplateEditRepository {

    private final DocTemplateEditJpaRepository jpa;

    public DocTemplateEditRepositoryImpl(DocTemplateEditJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<DocTemplateEdit> listByBlueprintVersion(int bankId, int blueprintId, int version) {
        return jpa.findByBankIdAndBlueprintIdAndVersionOrderByDocTemplateEditIdAsc(bankId, blueprintId, version);
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
    public List<DocTemplateEdit> saveAll(List<DocTemplateEdit> edits) {
        return jpa.saveAll(edits);
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
