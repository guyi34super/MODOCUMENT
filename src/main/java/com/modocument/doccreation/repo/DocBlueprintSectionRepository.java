package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintSection;

import java.util.List;
import java.util.Optional;

public interface DocBlueprintSectionRepository {

    Optional<DocBlueprintSection> getById(int id);

    /** Sections of a blueprint in document order. */
    List<DocBlueprintSection> listByBlueprint(int blueprintId);

    DocBlueprintSection save(DocBlueprintSection entity);

    void deleteByBlueprint(int blueprintId);
}
