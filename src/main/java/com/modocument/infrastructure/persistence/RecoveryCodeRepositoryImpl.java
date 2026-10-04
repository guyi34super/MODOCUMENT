package com.modocument.infrastructure.persistence;

import com.modocument.application.port.RecoveryCodeRepository;
import com.modocument.domain.entity.RecoveryCode;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class RecoveryCodeRepositoryImpl implements RecoveryCodeRepository {

    private final RecoveryCodeJpaRepository jpa;

    public RecoveryCodeRepositoryImpl(RecoveryCodeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<RecoveryCode> getUnusedByUserId(int userId) {
        return jpa.findByUserIdAndUsedAtIsNull(userId);
    }

    @Override
    public RecoveryCode save(RecoveryCode entity) {
        return jpa.save(entity);
    }

    @Override
    @Transactional
    public void deleteByUserId(int userId) {
        jpa.deleteByUserId(userId);
    }
}
