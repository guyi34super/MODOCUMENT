package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprint;

import java.util.List;
import java.util.Optional;

public interface DocBlueprintRepository {

    /** Owner-scoped read: empty for a blueprint the caller does not own. */
    Optional<DocBlueprint> getByIdAndOwner(int id, int ownerUserId);

    List<DocBlueprint> listByOwner(int ownerUserId);

    DocBlueprint save(DocBlueprint entity);

    void delete(int id);
}
