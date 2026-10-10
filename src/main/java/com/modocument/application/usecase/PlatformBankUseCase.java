package com.modocument.application.usecase;

import com.modocument.application.dto.BankIntegrations;
import com.modocument.application.dto.BankSummary;
import com.modocument.application.dto.PlatformOverview;
import com.modocument.application.port.PlatformBankStats;
import com.modocument.application.port.PlatformBankStats.BankCounts;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.BankStatus;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * The platform console's bank reads (ws21, PLATFORM_READ):
 * {@code GET /api/platform/overview}, {@code GET /api/platform/banks} and
 * {@code GET /api/platform/banks/{id}}.
 *
 * <p>A named cross-tenant read: the platform admin's own tenant is the
 * platform bank, so every other bank's rows are invisible to it under RLS.
 * This use case therefore reads as SYSTEM, in its own read-only
 * transaction (a registered {@code runAsSystem} call site, see
 * {@link TenantContext}) — and returns COUNTS ONLY: the Bank row's
 * identifying columns plus {@link PlatformBankStats} aggregates (on the
 * one-bank read also the integration health counts: connections, enabled
 * flows, 24 h runs by outcome, dead letters, offline agents). The platform
 * owner never sees a bank's users, templates, jobs, documents or any
 * integration configuration.
 */
@Service
public class PlatformBankUseCase {

    /** The activity window of the job counts (jobs7d / failures7d). */
    static final Duration WINDOW = Duration.ofDays(7);

    /** The window of the integration run counts (runs24h). */
    static final Duration RUNS_WINDOW = Duration.ofHours(24);

    private final BankJpaRepository banks;
    private final PlatformBankStats stats;
    private final SettingsReader settings;
    private final TransactionTemplate readOnlyNewTx;

    public PlatformBankUseCase(BankJpaRepository banks, PlatformBankStats stats,
                               SettingsReader settings,
                               PlatformTransactionManager transactionManager) {
        this.banks = banks;
        this.stats = stats;
        this.settings = settings;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // A NEW transaction, begun under runAsSystem: the tenant is bound to
        // the connection at transaction begin, so joining a caller's
        // (platform-bank) transaction would silently count only that bank.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        this.readOnlyNewTx = template;
    }

    /** Every bank (customer banks and the platform bank), ordered by code. */
    public List<BankSummary> listBanks() {
        return asSystem(() -> {
            Map<Integer, BankCounts> counts = stats.countsByBank(null, windowStart());
            return banks.findAll(Sort.by("code")).stream()
                    .map(bank -> summarize(bank, counts.get(bank.getBankId())))
                    .toList();
        });
    }

    /**
     * The platform console's landing view: every CUSTOMER bank's summary
     * plus totals across them (bank count by status, enabled accounts).
     * Counts only, like every read here.
     */
    public PlatformOverview overview() {
        List<BankSummary> customers = listBanks().stream()
                .filter(bank -> bank.kind() == BankKind.CUSTOMER)
                .toList();
        long suspended = customers.stream()
                .filter(bank -> bank.status() == BankStatus.SUSPENDED)
                .count();
        long accounts = customers.stream().mapToLong(BankSummary::users).sum();
        return new PlatformOverview(customers, new PlatformOverview.Totals(
                customers.size(), customers.size() - suspended, suspended, accounts));
    }

    /**
     * One bank's summary plus its integration health counts
     * ({@link BankIntegrations}); an unknown id is a 404.
     */
    public BankSummary getBank(int bankId) {
        Instant now = Instant.now();
        Instant heartbeatBefore = now.minus(heartbeatTimeout(bankId));
        return asSystem(() -> {
            Bank bank = banks.findById(bankId).orElseThrow(() ->
                    new NoSuchElementException("Bank " + bankId + " not found."));
            return summarize(bank, stats.countsByBank(bankId, windowStart()).get(bankId))
                    .withIntegrations(stats.integrationCounts(bankId, now.minus(RUNS_WINDOW),
                            heartbeatBefore));
        });
    }

    /** The bank's {@code integration.agentHeartbeatTimeout} (read from the settings snapshot). */
    private Duration heartbeatTimeout(int bankId) {
        return settings.getDuration(SettingKeys.INTEGRATION_AGENT_HEARTBEAT_TIMEOUT, bankId);
    }

    private static BankSummary summarize(Bank bank, BankCounts counts) {
        BankCounts c = counts == null ? BankCounts.EMPTY : counts;
        Integer mfaPct = c.activeUsers() == 0
                ? null
                : (int) Math.round(c.mfaEnrolledUsers() * 100.0 / c.activeUsers());
        return new BankSummary(bank.getBankId(), bank.getCode(), bank.getName(),
                bank.getKind(), bank.getStatus(), c.activeUsers(), mfaPct,
                c.jobsSince(), c.failedJobsSince(),
                // AI vs code parse shares need parse provenance (ws23, wave 2).
                null, null,
                c.lastSignInAt(), null);
    }

    private static LocalDateTime windowStart() {
        return LocalDateTime.now().minus(WINDOW);
    }

    private <T> T asSystem(Supplier<T> read) {
        try {
            return TenantContext.runAsSystem(() -> readOnlyNewTx.execute(status -> read.get()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Platform bank read failed.", e);
        }
    }
}
