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

    @Override
    public com.modocument.application.dto.PagedList<PendingChange> pageVisible(
            int bankId, PendingChangeStatus status, int makerUserId,
            java.util.Collection<com.modocument.domain.model.ChangeType> decidableTypes,
            int page, int size) {
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, size);
        boolean anyType = decidableTypes != null && !decidableTypes.isEmpty();
        org.springframework.data.domain.Page<PendingChange> rows;
        if (status == null) {
            rows = anyType
                    ? jpa.findVisible(bankId, makerUserId, decidableTypes, pageable)
                    : jpa.findByBankIdAndMakerUserIdOrderByRequestedAtDescPendingChangeIdDesc(
                            bankId, makerUserId, pageable);
        } else {
            rows = anyType
                    ? jpa.findVisibleInStatus(bankId, status, makerUserId, decidableTypes, pageable)
                    : jpa.findByBankIdAndStatusAndMakerUserIdOrderByRequestedAtDescPendingChangeIdDesc(
                            bankId, status, makerUserId, pageable);
        }
        return new com.modocument.application.dto.PagedList<>(rows.getContent(), page, size,
                rows.getTotalElements());
    }
}
