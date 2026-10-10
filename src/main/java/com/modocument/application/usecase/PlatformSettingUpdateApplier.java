package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.BankDirectory;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.infrastructure.settings.SettingsCache;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * PLATFORM_SETTING_UPDATE under dual control (ws20): the platform owner's
 * security-tier defaults and EVERY change of a bank's allowed range
 * (bankMin/bankMax). Payload
 * {@code {changes:[{key, from, to, bankMin?, bankMax?, baseVersion}]}} — a
 * bound key present means "set it" (null: clear).
 *
 * <p>The maker and the checker are both users of the platform bank (the
 * platform layer is that bank's rows; maker/checker same-bank equality is
 * asserted by PendingChangeUseCase). SUBMIT re-judges the changes as the
 * direct endpoint would (hard limits, bankMin ≤ bankMax, the platform value
 * inside the bank bounds, no open change on the key, version current); APPLY
 * re-judges them against the state NOW and writes only rows whose version
 * still equals {@code baseVersion} (else 409 SETTING_VERSION_CONFLICT and
 * the change is FAILED). Each key is audited PLATFORM_SETTING_UPDATED,
 * which the {@code platform-change} alert rule (HIGH) picks up.
 *
 * <p><b>Per-bank values</b> (platform-controlled settings, e.g. the allowed
 * API-client authentication methods): a payload with a top-level
 * {@code bankId} — {@code {bankId, changes:[{key, from, to, baseVersion}]}}
 * — sets that CUSTOMER bank's own value of platform-controlled keys only
 * ({@link SettingChangeService.Layer#BANK_BY_PLATFORM}). The change still
 * lives in, and is decided within, the platform bank; the row is written in
 * the target bank's tenant. The target must be an existing customer bank at
 * submit and at apply.
 */
@Component
public class PlatformSettingUpdateApplier implements PendingChangeApplier {

    private final SettingChangeService changes;
    private final CurrentUserProvider currentUser;
    private final PlatformBankLookup platformBanks;
    private final SettingsCache cache;
    private final BankDirectory banks;
    private final ObjectMapper objectMapper;

    public PlatformSettingUpdateApplier(SettingChangeService changes,
                                        CurrentUserProvider currentUser,
                                        PlatformBankLookup platformBanks, SettingsCache cache,
                                        BankDirectory banks, ObjectMapper objectMapper) {
        this.changes = changes;
        this.currentUser = currentUser;
        this.platformBanks = platformBanks;
        this.cache = cache;
        this.banks = banks;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.PLATFORM_SETTING_UPDATE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int bankId = currentUser.requireBankId();
        if (!platformBanks.isPlatformBank(bankId)) {
            throw new AccessDeniedException("Platform settings belong to the platform owner.");
        }
        Integer target = SettingChangeService.targetBankOf(payload);
        SettingChangeService.Layer layer = layerOf(target);
        cache.refreshIfChanged();
        List<SettingChangeService.ParsedChange> parsed = changes.validate(layer,
                target == null ? bankId : requireCustomerBank(target),
                SettingChangeService.fromPayload(payload), null, true);
        return new Prepared(changes.toPayload(layer, target, parsed),
                SettingUpdateApplier.targetOf(parsed));
    }

    @Override
    public void apply(PendingChange change) {
        int bankId = change.getBankId();
        if (!platformBanks.isPlatformBank(bankId)) {
            throw new IllegalStateException("A platform setting change must belong to the "
                    + "platform bank.");
        }
        cache.refreshIfChanged();
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        Integer target = SettingChangeService.targetBankOf(payload);
        SettingChangeService.Layer layer = layerOf(target);
        int layerBank = target == null ? bankId : requireCustomerBank(target);
        List<SettingChangeService.ParsedChange> parsed = changes.validate(layer, layerBank,
                SettingChangeService.fromPayload(payload), change.getPendingChangeId(), false);
        changes.write(layer, layerBank, parsed, layer.approvedAuditAction(),
                change.getMakerUserId());
        changes.bumpRevision();
    }

    private static SettingChangeService.Layer layerOf(Integer targetBank) {
        return targetBank == null ? SettingChangeService.Layer.PLATFORM
                : SettingChangeService.Layer.BANK_BY_PLATFORM;
    }

    /** The target of a per-bank change: an existing CUSTOMER bank (404 otherwise). */
    private int requireCustomerBank(int bankId) {
        BankDirectory.BankRef bank = banks.find(bankId)
                .orElseThrow(() -> new NoSuchElementException("Bank " + bankId + " not found."));
        if (bank.isPlatform() || platformBanks.isPlatformBank(bankId)) {
            throw new IllegalArgumentException("The platform bank's settings are the platform "
                    + "layer: use /api/platform/settings.");
        }
        return bankId;
    }
}
