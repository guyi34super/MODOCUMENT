package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.BankKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankJpaRepository extends JpaRepository<Bank, Integer> {

    Optional<Bank> findByCode(String code);

    /** ws21: the platform bank by kind (at most one row — V210 partial unique index). */
    List<Bank> findByKind(BankKind kind);
}
