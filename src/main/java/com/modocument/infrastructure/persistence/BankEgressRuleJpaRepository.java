package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.BankEgressRule;
import com.modocument.domain.model.integration.EgressRuleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ws25 egress rules (V251). Bank-scoped queries name the bank explicitly on
 * top of row level security, so the H2 suite (no RLS) filters the same way
 * PostgreSQL does; the status-only query is the platform console's
 * cross-bank read and runs as SYSTEM.
 */
public interface BankEgressRuleJpaRepository extends JpaRepository<BankEgressRule, Integer> {

    List<BankEgressRule> findByBankIdOrderByCreatedAtDescBankEgressRuleIdDesc(int bankId);

    List<BankEgressRule> findByBankIdAndStatus(int bankId, EgressRuleStatus status);

    List<BankEgressRule> findByBankIdAndStatusIn(int bankId, Collection<EgressRuleStatus> statuses);

    Optional<BankEgressRule> findByBankEgressRuleIdAndBankId(int id, int bankId);

    /** Platform console (as SYSTEM): every bank's rules in these states, newest first. */
    List<BankEgressRule> findByStatusInOrderByCreatedAtDescBankEgressRuleIdDesc(
            Collection<EgressRuleStatus> statuses);
}
