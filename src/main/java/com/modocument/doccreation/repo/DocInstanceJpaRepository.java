package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocInstanceJpaRepository extends JpaRepository<DocInstance, Integer> {

    Optional<DocInstance> findByDocInstanceIdAndOwnerUserId(int id, int ownerUserId);

    List<DocInstance> findByOwnerUserIdOrderByDocInstanceIdDesc(int ownerUserId);

    List<DocInstance> findByOwnerUserIdAndBlueprintIdOrderByDocInstanceIdDesc(int ownerUserId, int blueprintId);

    int countByBlueprintId(int blueprintId);

    /** Retention sweep (ws14): instances idle past the cutoff, content not yet purged. */
    List<DocInstance> findByBankIdAndUpdatedDateBeforeAndContentPurgedAtIsNull(
            int bankId, java.time.LocalDateTime cutoff);
}
