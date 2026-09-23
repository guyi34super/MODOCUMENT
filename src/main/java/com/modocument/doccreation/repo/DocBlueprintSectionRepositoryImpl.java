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
}
