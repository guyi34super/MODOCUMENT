package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.BankSetting;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * BankSetting persistence (V200, ws20). Writes always name the caller's own
 * BankId — the explicit filter backs up the PostgreSQL RLS policy and is the
 * only tenant boundary on the H2 test schema. The cross-bank
 * {@link #findAll()} is used by the settings cache only, as SYSTEM.
 */
public interface BankSettingJpaRepository extends JpaRepository<BankSetting, Integer> {

    Optional<BankSetting> findByBankIdAndSettingKey(int bankId, String settingKey);

    List<BankSetting> findByBankId(int bankId);

    /**
     * The row a write is about to change, locked (SELECT … FOR UPDATE) until
     * the caller's transaction ends: the version check and the write are one
     * atomic step even with concurrent writers on several tasks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BankSetting s WHERE s.bankId = :bankId AND s.settingKey = :key")
    Optional<BankSetting> lockByBankIdAndSettingKey(@Param("bankId") int bankId,
                                                    @Param("key") String settingKey);
}
