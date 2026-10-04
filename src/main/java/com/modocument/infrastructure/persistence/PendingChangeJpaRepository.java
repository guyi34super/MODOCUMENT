package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.PendingChangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingChangeJpaRepository extends JpaRepository<PendingChange, Integer> {

    List<PendingChange> findByBankIdOrderByRequestedAtDescPendingChangeIdDesc(int bankId);

    List<PendingChange> findByBankIdAndStatusOrderByRequestedAtDescPendingChangeIdDesc(
            int bankId, PendingChangeStatus status);
}
