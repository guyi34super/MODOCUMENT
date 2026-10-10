package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.infrastructure.settings.SettingsCache;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;

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
 */
@Component
public class PlatformSettingUpdateApplier implements PendingChangeApplier {

    private final SettingChangeService changes;
    private final CurrentUserProvider currentUser;
    private final PlatformBankLookup platformBanks;
    private final SettingsCache cache;
    private final ObjectMapper objectMapper;

    public PlatformSettingUpdateApplier(SettingChangeService changes,
                                        CurrentUserProvider currentUser,
                                        PlatformBankLookup platformBanks, SettingsCache cache,
                                        ObjectMapper objectMapper) {
        this.changes = changes;
        this.currentUser = currentUser;
        this.platformBanks = platformBanks;
        this.cache = cache;
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
        cache.refreshIfChanged();
        List<SettingChangeService.ParsedChange> parsed = changes.validate(
                SettingChangeService.Layer.PLATFORM, bankId,
                SettingChangeService.fromPayload(payload), null, true);
        return new Prepared(changes.toPayload(SettingChangeService.Layer.PLATFORM, parsed),
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
        List<SettingChangeService.ParsedChange> parsed = changes.validate(
                SettingChangeService.Layer.PLATFORM, bankId,
                SettingChangeService.fromPayload(payload), change.getPendingChangeId(), false);
        changes.write(SettingChangeService.Layer.PLATFORM, bankId, parsed,
                SettingChangeService.Layer.PLATFORM.approvedAuditAction(),
                change.getMakerUserId());
        changes.bumpRevision();
    }
}
