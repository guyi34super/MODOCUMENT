package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprint;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class DocBlueprintRepositoryImpl implements DocBlueprintRepository {

    private final DocBlueprintJpaRepository jpa;

    public DocBlueprintRepositoryImpl(DocBlueprintJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<DocBlueprint> getByIdAndOwner(int id, int ownerUserId) {
        return jpa.findByDocBlueprintIdAndOwnerUserId(id, ownerUserId);
    }

    @Override
    public List<DocBlueprint> listByOwner(int ownerUserId) {
        return jpa.findByOwnerUserIdOrderByDocBlueprintIdDesc(ownerUserId);
    }

    @Override
    public DocBlueprint save(DocBlueprint entity) {
        return jpa.save(entity);
    }

    @Override
    public void delete(int id) {
        jpa.findById(id).ifPresent(jpa::delete);
    }
}
