package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocTemplateRegion;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * V301: the regions of template versions. Every read and delete names the
 * caller's bank (the explicit filter is the H2-test and defense-in-depth
 * layer under the PostgreSQL RLS policy).
 */
public interface DocTemplateRegionRepository {

    /** One version's regions in reading order. */
    List<DocTemplateRegion> listByBlueprintVersion(int bankId, int blueprintId, int version);

    /** Every version's regions of several blueprints, keyed by blueprint id (release queue). */
    Map<Integer, List<DocTemplateRegion>> listByBlueprints(int bankId, Collection<Integer> blueprintIds);

    /** How many regions each version of one blueprint has, keyed by version. */
    Map<Integer, Long> countByVersion(int bankId, int blueprintId);

    List<DocTemplateRegion> saveAll(List<DocTemplateRegion> regions);

    void deleteByBlueprintVersion(int bankId, int blueprintId, int version);

    void deleteByBlueprint(int bankId, int blueprintId);
}
