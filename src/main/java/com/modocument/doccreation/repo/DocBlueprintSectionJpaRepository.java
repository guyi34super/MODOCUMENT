package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocBlueprintSectionJpaRepository extends JpaRepository<DocBlueprintSection, Integer> {

    List<DocBlueprintSection> findByBlueprintIdOrderByOrderIndex(int blueprintId);

    void deleteByBlueprintId(int blueprintId);

    /** ws28: the sections of several blueprints, in blueprint then document order. */
    List<DocBlueprintSection> findByBlueprintIdInOrderByBlueprintIdAscOrderIndexAsc(
            java.util.Collection<Integer> blueprintIds);

    /** ws28: rows of [BlueprintId, count (Long)]. */
    @org.springframework.data.jpa.repository.Query("select s.blueprintId, count(s) from DocBlueprintSection s"
            + " where s.blueprintId in :blueprintIds group by s.blueprintId")
    List<Object[]> countByBlueprintIds(
            @org.springframework.data.repository.query.Param("blueprintIds") java.util.Collection<Integer> blueprintIds);
}
