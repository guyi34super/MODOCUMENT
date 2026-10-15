package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintVersion;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class DocBlueprintVersionRepositoryImpl implements DocBlueprintVersionRepository {

    private final DocBlueprintVersionJpaRepository jpa;

    public DocBlueprintVersionRepositoryImpl(DocBlueprintVersionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<DocBlueprintVersion> listByBlueprint(int blueprintId) {
        return jpa.findByBlueprintIdOrderByVersionDesc(blueprintId);
    }

    @Override
    public Optional<DocBlueprintVersion> get(int blueprintId, int version) {
        return jpa.findByBlueprintIdAndVersion(blueprintId, version);
    }

    @Override
    public DocBlueprintVersion save(DocBlueprintVersion entity) {
        return jpa.save(entity);
    }

    @Override
    public void deleteByBlueprint(int blueprintId) {
        jpa.deleteByBlueprintId(blueprintId);
    }
}
