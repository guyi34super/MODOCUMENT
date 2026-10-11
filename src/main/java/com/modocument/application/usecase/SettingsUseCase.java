package com.modocument.application.usecase;

import com.modocument.application.dto.PendingChangeDto;
import com.modocument.application.dto.PlatformSettingView;
import com.modocument.application.dto.SettingView;
import com.modocument.application.dto.SettingsPage;
import com.modocument.application.dto.SettingsUpdateRequest;
import com.modocument.application.dto.SettingsUpdateResult;
import com.modocument.application.port.BankDirectory;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingException;
import com.modocument.domain.model.settings.SettingGroup;
import com.modocument.domain.model.settings.SettingScope;
import com.modocument.domain.model.settings.SettingTier;
import com.modocument.infrastructure.settings.SettingsCache;
import com.modocument.infrastructure.settings.SettingsCatalogue;
import com.modocument.infrastructure.settings.SettingsResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

import static com.modocument.application.usecase.SettingChangeService.Layer;
import static com.modocument.application.usecase.SettingChangeService.ParsedChange;

/**
 * The settings screens (ws20): read and change the BANK layer
 * ({@code /api/admin/settings}, a bank's own overrides) and the PLATFORM
 * layer ({@code /api/platform/settings}, the platform owner's defaults and
 * bank bounds — the platform bank's rows).
 *
 * <p>A PUT is judged as a whole ({@link SettingChangeService}); any refusal
 * leaves everything unchanged. Then, in ONE transaction:
 * <ul>
 *   <li>OPERATIONAL changes apply at once — row written, audited
 *       SETTING_UPDATED with old/new, ConfigRevision bumped;</li>
 *   <li>SECURITY changes (and every platform bank-bound change) become ONE
 *       maker/checker request (SETTING_UPDATE / PLATFORM_SETTING_UPDATE)
 *       that a different user must approve; the answer is then 202.</li>
 * </ul>
 * This task's snapshot reloads right after the commit, so the answer (and
 * the next GET) already shows the new values; other tasks follow within one
 * poll interval.
 *
 * <p>Who: the bank layer refuses callers of the platform bank (409
 * USE_PLATFORM_SETTINGS — their settings ARE the platform layer); the
 * platform layer refuses anyone outside the platform bank (403), on top of
 * the route permissions.
 *
 * <p><b>Platform-controlled values of one bank</b>
 * ({@code /api/platform/banks/{id}/settings}): the platform owner reads and
 * sets a CUSTOMER bank's own value of the platform-controlled settings
 * (e.g. the allowed API-client authentication methods), which that bank's
 * administrators see read-only. Same rules as the other layers; a security
 * value is one PLATFORM_SETTING_UPDATE naming the bank, approved by a second
 * platform admin.
 */
@Service
public class SettingsUseCase {

    private final CurrentUserProvider currentUser;
    private final PlatformBankLookup platformBanks;
    private final SettingsCatalogue catalogue;
    private final SettingsResolver resolver;
    private final SettingsCache cache;
    private final SettingChangeService changes;
    private final PendingChangeUseCase pendingChanges;
    private final BankDirectory banks;
    private final TransactionTemplate tx;

    public SettingsUseCase(CurrentUserProvider currentUser, PlatformBankLookup platformBanks,
                           SettingsCatalogue catalogue, SettingsResolver resolver,
                           SettingsCache cache, SettingChangeService changes,
                           PendingChangeUseCase pendingChanges, BankDirectory banks,
                           PlatformTransactionManager transactionManager) {
        this.currentUser = currentUser;
        this.platformBanks = platformBanks;
        this.catalogue = catalogue;
        this.resolver = resolver;
        this.cache = cache;
        this.changes = changes;
        this.pendingChanges = pendingChanges;
        this.banks = banks;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------ bank layer

    /** Every BANK-scope setting as the caller's bank sees it, by tab. */
    public SettingsPage<SettingView> bankSettings() {
        int bankId = requireCustomerBank();
        cache.refreshIfChanged();
        Map<String, Integer> open = changes.openChangeKeys(Layer.BANK, bankId);
        return page(Layer.BANK, catalogue.ofScope(SettingScope.BANK),
                definition -> bankView(definition, bankId, open));
    }

    /** Applies operational changes, submits security ones; see the class comment. */
    public SettingsUpdateResult<SettingView> updateBankSettings(SettingsUpdateRequest request) {
        int bankId = requireCustomerBank();
        Outcome outcome = update(Layer.BANK, bankId, request);
        Map<String, Integer> open = changes.openChangeKeys(Layer.BANK, bankId);
        List<SettingView> applied = outcome.appliedKeys().stream()
                .map(key -> bankView(catalogue.require(key), bankId, open))
                .toList();
        return new SettingsUpdateResult<>(applied, outcome.pendingChange());
    }

    // -------------------------------------------------------- platform layer

    /** Every setting at the platform layer (values, hard limits, bank bounds), by tab. */
    public SettingsPage<PlatformSettingView> platformSettings() {
        int bankId = requirePlatformBank();
        cache.refreshIfChanged();
        Map<String, Integer> open = changes.openChangeKeys(Layer.PLATFORM, bankId);
        return page(Layer.PLATFORM, catalogue.all(), definition -> platformView(definition, open));
    }

    /** Platform values (operational: at once) and security values/bounds (maker/checker). */
    public SettingsUpdateResult<PlatformSettingView> updatePlatformSettings(
            SettingsUpdateRequest request) {
        int bankId = requirePlatformBank();
        Outcome outcome = update(Layer.PLATFORM, bankId, request);
        Map<String, Integer> open = changes.openChangeKeys(Layer.PLATFORM, bankId);
        List<PlatformSettingView> applied = outcome.appliedKeys().stream()
                .map(key -> platformView(catalogue.require(key), open))
                .toList();
        return new SettingsUpdateResult<>(applied, outcome.pendingChange());
    }

    // ------------------------------------- platform-controlled values of one bank

    /** The platform-controlled settings of one customer bank, as that bank sees them. */
    public SettingsPage<SettingView> bankSettingsForPlatform(int targetBankId) {
        requirePlatformBank();
        int bankId = requireCustomerTarget(targetBankId);
        cache.refreshIfChanged();
        Map<String, Integer> open = changes.openChangeKeys(Layer.BANK_BY_PLATFORM, bankId);
        return page(Layer.BANK_BY_PLATFORM, platformControlled(),
                definition -> bankView(definition, bankId, open));
    }

    /** Sets one customer bank's platform-controlled values (security: maker/checker). */
    public SettingsUpdateResult<SettingView> updateBankSettingsForPlatform(
            int targetBankId, SettingsUpdateRequest request) {
        requirePlatformBank();
        int bankId = requireCustomerTarget(targetBankId);
        Outcome outcome = update(Layer.BANK_BY_PLATFORM, bankId, request);
        Map<String, Integer> open = changes.openChangeKeys(Layer.BANK_BY_PLATFORM, bankId);
        List<SettingView> applied = outcome.appliedKeys().stream()
                .map(key -> bankView(catalogue.require(key), bankId, open))
                .toList();
        return new SettingsUpdateResult<>(applied, outcome.pendingChange());
    }

    private List<SettingDefinition> platformControlled() {
        return catalogue.ofScope(SettingScope.BANK).stream()
                .filter(SettingDefinition::platformControlled)
                .toList();
    }

    /** An existing customer bank (404 for an unknown id, 409 for the platform bank). */
    private int requireCustomerTarget(int bankId) {
        BankDirectory.BankRef bank = banks.find(bankId)
                .orElseThrow(() -> new NoSuchElementException("Bank " + bankId + " not found."));
        if (bank.isPlatform() || platformBanks.isPlatformBank(bankId)) {
            throw new SettingException(SettingException.Code.USE_PLATFORM_SETTINGS, null,
                    "The platform bank's settings are the platform layer: use "
                            + "/api/platform/settings.");
        }
        return bankId;
    }

    // ------------------------------------------------------------ plumbing

    private Outcome update(Layer layer, int bankId, SettingsUpdateRequest request) {
        // Judge against the newest committed limits (another task may have
        // moved the platform bounds within the last poll interval).
        cache.refreshIfChanged();
        return tx.execute(status -> {
            List<ParsedChange> parsed = changes.validate(layer, bankId,
                    SettingChangeService.fromRequest(request), null, true);
            List<ParsedChange> direct = parsed.stream()
                    .filter(change -> !change.requiresApproval()).toList();
            List<ParsedChange> approval = parsed.stream()
                    .filter(ParsedChange::requiresApproval).toList();
            if (!direct.isEmpty()) {
                changes.write(layer, bankId, direct, SettingChangeService.DIRECT_AUDIT_ACTION,
                        currentUser.requireUserId());
                changes.bumpRevision();
            }
            PendingChangeDto pending = approval.isEmpty() ? null
                    : pendingChanges.submit(layer.changeType(),
                    changes.toPayload(layer,
                            layer == Layer.BANK_BY_PLATFORM ? bankId : null, approval),
                    request.comment());
            return new Outcome(direct.stream().map(change -> change.definition().key()).toList(),
                    pending);
        });
    }

    private int requireCustomerBank() {
        int bankId = currentUser.requireBankId();
        if (platformBanks.isPlatformBank(bankId)) {
            throw new SettingException(SettingException.Code.USE_PLATFORM_SETTINGS, null,
                    "The platform bank's settings are the platform layer: use "
                            + "/api/platform/settings.");
        }
        return bankId;
    }

    private int requirePlatformBank() {
        int bankId = currentUser.requireBankId();
        if (!platformBanks.isPlatformBank(bankId)) {
            throw new AccessDeniedException("Platform settings belong to the platform owner.");
        }
        return bankId;
    }

    private <V> SettingsPage<V> page(Layer layer, List<SettingDefinition> definitions,
                                     Function<SettingDefinition, V> view) {
        List<SettingsPage.Group<V>> groups = new ArrayList<>();
        for (Map.Entry<SettingGroup, List<SettingDefinition>> group
                : catalogue.grouped(definitions).entrySet()) {
            groups.add(new SettingsPage.Group<>(group.getKey().id(), group.getKey().label(),
                    group.getValue().stream().map(view).toList()));
        }
        return new SettingsPage<>(layer.name(), cache.current().revision(), groups);
    }

    private SettingView bankView(SettingDefinition definition, int bankId,
                                 Map<String, Integer> open) {
        return view(definition, resolver.resolve(definition, bankId), open);
    }

    private PlatformSettingView platformView(SettingDefinition definition,
                                             Map<String, Integer> open) {
        SettingsResolver.Resolution resolution = resolver.resolveForPlatform(definition);
        return PlatformSettingView.of(view(definition, resolution, open),
                SettingChangeService.toJson(definition, resolution.bankMin()),
                SettingChangeService.toJson(definition, resolution.bankMax()),
                definition.hasBankBounds());
    }

    private SettingView view(SettingDefinition definition, SettingsResolver.Resolution resolution,
                             Map<String, Integer> open) {
        return new SettingView(
                definition.key(),
                definition.type().name(),
                definition.tier().name(),
                definition.scope().name(),
                definition.group().id(),
                definition.label(),
                definition.description(),
                definition.unit(),
                SettingChangeService.toJson(definition, resolution.value()),
                resolution.source().name(),
                SettingChangeService.toJson(definition, catalogue.defaultValue(definition)),
                SettingChangeService.toJson(definition, resolution.min()),
                SettingChangeService.toJson(definition, resolution.max()),
                SettingChangeService.toJson(definition, definition.hardMin()),
                SettingChangeService.toJson(definition, definition.hardMax()),
                definition.allowedForDisplay(),
                definition.tier() == SettingTier.SECURITY,
                definition.requiresRestart(),
                definition.locked(),
                resolution.clamped(),
                open.get(definition.key()),
                resolution.version(),
                definition.platformControlled());
    }

    /** What one PUT did: the keys applied at once and the pending request, if any. */
    private record Outcome(List<String> appliedKeys, PendingChangeDto pendingChange) {
    }
}
