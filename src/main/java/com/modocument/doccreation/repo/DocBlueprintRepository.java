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

    /** The owner's templates of one kind (ws31: the lists show PDF_OVERLAY only). */
    List<DocBlueprint> listByOwnerAndKind(int ownerUserId, String kind);

    /** One bank's templates of one kind (the legacy purge; RLS backs the bank filter). */
    List<DocBlueprint> listByBankAndKind(int bankId, String kind);

    DocBlueprint save(DocBlueprint entity);

    void delete(int id);

    /**
     * ws28: several blueprints by id in one statement (keyed by id; missing
     * ids absent). Same caveat as {@link #getById}: release paths only.
     */
    default java.util.Map<Integer, DocBlueprint> getByIds(java.util.Collection<Integer> ids) {
        java.util.Map<Integer, DocBlueprint> byId = new java.util.LinkedHashMap<>();
        for (Integer id : ids) {
            getById(id).ifPresent(blueprint -> byId.put(id, blueprint));
        }
        return byId;
    }
}
