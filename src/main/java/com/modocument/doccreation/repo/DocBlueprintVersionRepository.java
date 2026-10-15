package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintVersion;

import java.util.List;
import java.util.Optional;

/** V291: the section-structure versions of blueprints. */
public interface DocBlueprintVersionRepository {

    /** Newest version first. */
    List<DocBlueprintVersion> listByBlueprint(int blueprintId);

    Optional<DocBlueprintVersion> get(int blueprintId, int version);

    DocBlueprintVersion save(DocBlueprintVersion entity);

    void deleteByBlueprint(int blueprintId);
}
