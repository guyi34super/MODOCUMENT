package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocTemplateEdit;

import java.util.List;
import java.util.Map;

/** V302: the edits of template versions; every call names the caller's bank. */
public interface DocTemplateEditRepository {

    List<DocTemplateEdit> listByBlueprintVersion(int bankId, int blueprintId, int version);

    /** How many edits each version of one blueprint has, keyed by version. */
    Map<Integer, Long> countByVersion(int bankId, int blueprintId);

    List<DocTemplateEdit> saveAll(List<DocTemplateEdit> edits);

    void deleteByBlueprintVersion(int bankId, int blueprintId, int version);

    void deleteByBlueprint(int bankId, int blueprintId);
}
