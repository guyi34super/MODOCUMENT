package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.RecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface RecoveryCodeJpaRepository extends JpaRepository<RecoveryCode, Integer> {

    List<RecoveryCode> findByUserIdAndUsedAtIsNull(int userId);

    // Derived deletes need a transaction of their own when no caller
    // transaction is active (cleanup paths).
    @Transactional
    void deleteByUserId(int userId);
}
