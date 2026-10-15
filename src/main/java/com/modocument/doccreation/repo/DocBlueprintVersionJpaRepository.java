package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocBlueprintVersionJpaRepository extends JpaRepository<DocBlueprintVersion, Integer> {

    List<DocBlueprintVersion> findByBlueprintIdOrderByVersionDesc(int blueprintId);

    Optional<DocBlueprintVersion> findByBlueprintIdAndVersion(int blueprintId, int version);

    void deleteByBlueprintId(int blueprintId);
}
