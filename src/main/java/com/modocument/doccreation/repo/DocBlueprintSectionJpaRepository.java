package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocBlueprintSectionJpaRepository extends JpaRepository<DocBlueprintSection, Integer> {

    List<DocBlueprintSection> findByBlueprintIdOrderByOrderIndex(int blueprintId);

    void deleteByBlueprintId(int blueprintId);
}
