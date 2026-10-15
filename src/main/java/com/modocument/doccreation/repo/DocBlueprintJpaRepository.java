package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocBlueprintJpaRepository extends JpaRepository<DocBlueprint, Integer> {

    Optional<DocBlueprint> findByDocBlueprintIdAndOwnerUserId(int id, int ownerUserId);

    List<DocBlueprint> findByOwnerUserIdOrderByDocBlueprintIdDesc(int ownerUserId);

    List<DocBlueprint> findByOwnerUserIdAndKindOrderByDocBlueprintIdDesc(int ownerUserId, String kind);

    List<DocBlueprint> findByBankIdAndKindOrderByDocBlueprintIdAsc(int bankId, String kind);
}
