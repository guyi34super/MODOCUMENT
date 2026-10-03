package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.Bank;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BankJpaRepository extends JpaRepository<Bank, Integer> {

    Optional<Bank> findByCode(String code);
}
