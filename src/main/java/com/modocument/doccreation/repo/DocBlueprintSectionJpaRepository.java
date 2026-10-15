package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocBlueprintSectionJpaRepository extends JpaRepository<DocBlueprintSection, Integer> {

    List<DocBlueprintSection> findByBlueprintIdOrderByVersionAscOrderIndexAsc(int blueprintId);

    List<DocBlueprintSection> findByBlueprintIdAndVersionOrderByOrderIndex(int blueprintId, int version);

    void deleteByBlueprintId(int blueprintId);

    /** ws28: the sections of several blueprints, in blueprint, version then document order. */
    List<DocBlueprintSection> findByBlueprintIdInOrderByBlueprintIdAscVersionAscOrderIndexAsc(
            java.util.Collection<Integer> blueprintIds);

    /** ws28 + V291: rows of [BlueprintId, Version, count (Long)]. */
    @org.springframework.data.jpa.repository.Query("select s.blueprintId, s.version, count(s) from DocBlueprintSection s"
            + " where s.blueprintId in :blueprintIds group by s.blueprintId, s.version")
    List<Object[]> countByBlueprintIdsAndVersion(
            @org.springframework.data.repository.query.Param("blueprintIds") java.util.Collection<Integer> blueprintIds);
}
