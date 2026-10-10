package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprint;

import java.util.List;
import java.util.Optional;

public interface DocBlueprintRepository {

    /** Owner-scoped read: empty for a blueprint the caller does not own. */
    Optional<DocBlueprint> getByIdAndOwner(int id, int ownerUserId);

    /**
     * Unscoped read for the ws11 release workflow ONLY: an approver reviews
     * ANOTHER user's instance and needs its blueprint's name/sections.
     * Callers must have already authorized access to an instance of this
     * blueprint through the bank-scoped release paths — never use this for
     * owner-facing reads.
     */
    Optional<DocBlueprint> getById(int id);

    List<DocBlueprint> listByOwner(int ownerUserId);

    DocBlueprint save(DocBlueprint entity);

    void delete(int id);
}
