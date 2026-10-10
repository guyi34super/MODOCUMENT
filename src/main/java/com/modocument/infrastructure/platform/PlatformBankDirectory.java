package com.modocument.infrastructure.platform;

import com.modocument.application.port.BankDirectory;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.BankKind;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The bank directory (ws21): resolves the platform bank and answers kind and
 * status lookups for any bank.
 *
 * <p>{@link PlatformBankLookup}: the platform bank is the bank whose code is
 * {@code modocument.platform.bank-code} (default {@code MOI-PLATFORM})
 * <em>and</em> whose Kind is PLATFORM — a customer bank that merely carries
 * the code is never treated as the platform (defense in depth under the
 * V210 trigger, which keeps PLATFORM_ADMIN users out of it anyway).
 *
 * <p>{@link BankDirectory}: per-bank kind/status.
 *
 * <p>Both are cached for {@code modocument.platform.bank-cache-ttl}
 * (default 30 s; 0 disables caching, as the H2 test profile does); per-bank
 * entries are also dropped on {@link #refresh(int)}. "Not found" is never
 * cached — the platform bank is created at startup by
 * BootstrapPlatformRunner, and banks are onboarded at runtime.
 *
 * <p>The Bank table is RLS-protected, so every read runs as system in its
 * own read-only transaction (registered {@code runAsSystem} call sites, see
 * {@link TenantContext}) and returns only the identifying columns.
 */
@Component
public class PlatformBankDirectory implements PlatformBankLookup, BankDirectory {

    private static final Logger log = LoggerFactory.getLogger(PlatformBankDirectory.class);

    private final BankJpaRepository banks;
    private final TransactionTemplate readOnlyNewTx;
    private final String platformBankCode;
    private final long ttlNanos;
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();
    private volatile CachedId cachedId;

    public PlatformBankDirectory(BankJpaRepository banks,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${modocument.platform.bank-code:MOI-PLATFORM}")
                                 String platformBankCode,
                                 @Value("${modocument.platform.bank-cache-ttl:30s}")
                                 Duration cacheTtl) {
        this.banks = banks;
        this.platformBankCode = platformBankCode;
        this.ttlNanos = cacheTtl.toNanos();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        this.readOnlyNewTx = template;
    }

    @Override
    public OptionalInt platformBankId() {
        CachedId cached = cachedId;
        long now = System.nanoTime();
        if (cached != null && now - cached.loadedAtNanos() < ttlNanos) {
            return OptionalInt.of(cached.bankId());
        }
        Optional<Bank> bank = readAsSystem(() -> banks.findByCode(platformBankCode),
                "Could not resolve the platform bank");
        if (bank == null || bank.isEmpty()) {
            cachedId = null;
            return OptionalInt.empty();
        }
        if (bank.get().getKind() != BankKind.PLATFORM) {
            log.warn("Bank '{}' carries the platform bank code but is a {} bank; it is NOT "
                    + "treated as the platform bank.", platformBankCode, bank.get().getKind());
            cachedId = null;
            return OptionalInt.empty();
        }
        cachedId = new CachedId(bank.get().getBankId(), now);
        return OptionalInt.of(bank.get().getBankId());
    }

    /** The configured platform bank code. */
    public String platformBankCode() {
        return platformBankCode;
    }

    @Override
    public Optional<BankRef> find(int bankId) {
        Cached cached = cache.get(bankId);
        long now = System.nanoTime();
        if (cached != null && now - cached.loadedAtNanos() < ttlNanos) {
            return Optional.of(cached.ref());
        }
        Optional<Bank> bank = readAsSystem(() -> banks.findById(bankId),
                "Could not read bank " + bankId);
        if (bank == null || bank.isEmpty()) {
            cache.remove(bankId);
            return Optional.empty();
        }
        Bank found = bank.get();
        BankRef ref = new BankRef(found.getBankId(), found.getCode(), found.getName(),
                found.getKind(), found.getStatus());
        cache.put(bankId, new Cached(ref, now));
        return Optional.of(ref);
    }

    @Override
    public void refresh(int bankId) {
        cache.remove(bankId);
    }

    private <T> T readAsSystem(Supplier<T> query, String failure) {
        try {
            return TenantContext.runAsSystem(() -> readOnlyNewTx.execute(status -> query.get()));
        } catch (Exception e) {
            throw new IllegalStateException(failure, e);
        }
    }

    private record Cached(BankRef ref, long loadedAtNanos) {
    }

    private record CachedId(int bankId, long loadedAtNanos) {
    }
}
