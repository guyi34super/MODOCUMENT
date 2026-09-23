package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocInstance;

import java.util.List;
import java.util.Optional;

public interface DocInstanceRepository {

    /** Owner-scoped read: empty for an instance the caller does not own. */
    Optional<DocInstance> getByIdAndOwner(int id, int ownerUserId);

    List<DocInstance> listByOwner(int ownerUserId);

    List<DocInstance> listByOwnerAndBlueprint(int ownerUserId, int blueprintId);

    int countByBlueprint(int blueprintId);

    DocInstance save(DocInstance entity);

    void delete(int id);
}
