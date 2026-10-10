package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.settings.SettingException;
import com.modocument.infrastructure.settings.SettingsCache;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SETTING_UPDATE under dual control (ws20): security-tier changes to a
 * bank's own settings. Payload {@code {changes:[{key, from, to, baseVersion}]}}.
 *
 * <p>SUBMIT (as the maker) re-judges every change with the rules of the
 * direct endpoint — bank-scope key, not locked, inside the bank's effective
 * range, no other open change on the key, version still current — and
 * re-reads {@code from} from the database rather than trusting the maker.
 * APPLY (as the checker, in the approval's transaction) judges the changes
 * again against the limits in force NOW (the platform may have narrowed the
 * bank bounds meanwhile), then writes each row only if its version still
 * equals {@code baseVersion}: a row that moved answers 409
 * SETTING_VERSION_CONFLICT and PendingChangeUseCase marks the change FAILED.
 * Each key is audited SECURITY_SETTING_UPDATED (old/new values), which the
 * {@code security-setting-changed} alert rule picks up.
 */
@Component
public class SettingUpdateApplier implements PendingChangeApplier {

    private final SettingChangeService changes;
    private final CurrentUserProvider currentUser;
    private final PlatformBankLookup platformBanks;
    private final SettingsCache cache;
    private final ObjectMapper objectMapper;

    public SettingUpdateApplier(SettingChangeService changes, CurrentUserProvider currentUser,
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
        return ChangeType.SETTING_UPDATE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        int bankId = currentUser.requireBankId();
        if (platformBanks.isPlatformBank(bankId)) {
            throw new SettingException(SettingException.Code.USE_PLATFORM_SETTINGS, null,
                    "The platform bank's settings are the platform layer: use "
                            + "/api/platform/settings.");
        }
        cache.refreshIfChanged();
        List<SettingChangeService.ParsedChange> parsed = changes.validate(
                SettingChangeService.Layer.BANK, bankId,
                SettingChangeService.fromPayload(payload), null, true);
        return new Prepared(changes.toPayload(SettingChangeService.Layer.BANK, parsed),
                targetOf(parsed));
    }

    @Override
    public void apply(PendingChange change) {
        int bankId = change.getBankId();
        if (platformBanks.isPlatformBank(bankId)) {
            throw new IllegalStateException("A bank setting change cannot apply to the "
                    + "platform bank.");
        }
        cache.refreshIfChanged();
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        List<SettingChangeService.ParsedChange> parsed = changes.validate(
                SettingChangeService.Layer.BANK, bankId,
                SettingChangeService.fromPayload(payload), change.getPendingChangeId(), false);
        changes.write(SettingChangeService.Layer.BANK, bankId, parsed,
                SettingChangeService.Layer.BANK.approvedAuditAction(), change.getMakerUserId());
        changes.bumpRevision();
    }

    /** One key → that key; several → null (the payload lists them). */
    static String targetOf(List<SettingChangeService.ParsedChange> parsed) {
        return parsed.size() == 1 ? parsed.get(0).definition().key() : null;
    }
}
