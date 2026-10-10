package com.modocument.infrastructure.persistence;

import com.modocument.application.port.PendingChangeRepository;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.PendingChangeStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PendingChangeRepositoryImpl implements PendingChangeRepository {

    private final PendingChangeJpaRepository jpa;

    public PendingChangeRepositoryImpl(PendingChangeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PendingChange save(PendingChange entity) {
        return jpa.save(entity);
    }

    @Override
    public Optional<PendingChange> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public List<PendingChange> listByBank(int bankId) {
        return jpa.findByBankIdOrderByRequestedAtDescPendingChangeIdDesc(bankId);
    }

    @Override
    public List<PendingChange> listByBankAndStatus(int bankId, PendingChangeStatus status) {
        return jpa.findByBankIdAndStatusOrderByRequestedAtDescPendingChangeIdDesc(bankId, status);
    }

    @Override
    public List<PendingChange> listAppliedSodViolations(int bankId) {
        return jpa.findAppliedSodViolations(bankId);
    }
}
