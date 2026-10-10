package com.modocument.infrastructure.settings;

import com.modocument.domain.entity.BankSetting;
import com.modocument.domain.entity.ConfigRevision;
import com.modocument.domain.model.settings.SettingException;
import com.modocument.infrastructure.persistence.BankSettingJpaRepository;
import com.modocument.infrastructure.persistence.ConfigRevisionJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * The write side of the settings tables (ws20). Every method runs in the
 * CALLER's transaction, under the caller's tenant (RLS: a bank only ever
 * touches its own rows; the platform layer is written by platform-bank
 * users) — the use case and the maker/checker appliers own the transaction
 * boundary and the validation.
 *
 * <p>Concurrency: {@link #write} locks the row, compares its version with
 * the version the caller read (0 = no row) and bumps it — a stale page or a
 * stale pending change gets {@code SETTING_VERSION_CONFLICT} instead of
 * silently overwriting a newer value. Two first writes racing on a missing
 * row meet the unique (BankId, SettingKey) key: the loser gets the same
 * conflict.
 */
@Component
public class SettingsStore {

    private final BankSettingJpaRepository rows;
    private final ConfigRevisionJpaRepository revisions;
    private final SettingsCache cache;

    public SettingsStore(BankSettingJpaRepository rows, ConfigRevisionJpaRepository revisions,
                         SettingsCache cache) {
        this.rows = rows;
        this.revisions = revisions;
        this.cache = cache;
    }

    /** A bank's stored row, read in the caller's transaction (fresh, not cached). */
    public Optional<BankSetting> find(int bankId, String key) {
        return rows.findByBankIdAndSettingKey(bankId, key);
    }

    /** The stored row's version, 0 when there is none. */
    public int versionOf(int bankId, String key) {
        return find(bankId, key).map(BankSetting::getVersion).orElse(0);
    }

    /**
     * Applies {@code change} to the bank's row for {@code key} (creating it on
     * first write) if, and only if, its version still equals
     * {@code expectedVersion}.
     *
     * @throws SettingException SETTING_VERSION_CONFLICT when the row moved
     */
    public BankSetting write(int bankId, String key, int expectedVersion, RowChange change,
                             Integer userId) {
        BankSetting row = rows.lockByBankIdAndSettingKey(bankId, key).orElse(null);
        int current = row == null ? 0 : row.getVersion();
        if (current != expectedVersion) {
            throw conflict(key, expectedVersion, current);
        }
        if (row == null) {
            row = new BankSetting(bankId, key);
        }
        if (change.setValue()) {
            row.setValueText(change.valueText());
        }
        if (change.setBankMin()) {
            row.setBankMinText(change.bankMinText());
        }
        if (change.setBankMax()) {
            row.setBankMaxText(change.bankMaxText());
        }
        row.setVersion(current + 1);
        row.setUpdatedByUserId(userId);
        row.setUpdatedAt(Instant.now());
        try {
            return rows.saveAndFlush(row);
        } catch (DataIntegrityViolationException concurrentFirstWrite) {
            throw conflict(key, expectedVersion, -1);
        }
    }

    /**
     * Bumps ConfigRevision in the caller's transaction (other tasks reload
     * on their next poll) and reloads this task once it commits. Call once
     * per transaction, after the writes.
     */
    public void bumpRevision() {
        Instant now = Instant.now();
        if (revisions.bump(now) == 0) {
            // Fresh H2 schema recreated under a running context: start over.
            revisions.save(new ConfigRevision(1L, now));
        }
        cache.reloadAfterCommit();
    }

    private static SettingException conflict(String key, int expected, int current) {
        return new SettingException(SettingException.Code.SETTING_VERSION_CONFLICT, key,
                "Setting " + key + " was changed by someone else (version " + expected
                        + " expected" + (current >= 0 ? ", " + current + " found" : "")
                        + "). Reload the settings and try again.");
    }

    /**
     * What a write changes: each part is applied only when its {@code set*}
     * flag is true (a null text then means "inherit" / "no bound").
     */
    public record RowChange(boolean setValue, String valueText, boolean setBankMin,
                            String bankMinText, boolean setBankMax, String bankMaxText) {

        /** A value-only change (bank overrides, platform values). */
        public static RowChange value(String valueText) {
            return new RowChange(true, valueText, false, null, false, null);
        }
    }
}
