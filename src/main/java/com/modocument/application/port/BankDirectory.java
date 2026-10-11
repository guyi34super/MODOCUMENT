package com.modocument.application.port;

import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.BankStatus;

import java.util.Optional;

/**
 * Cached, cross-tenant lookups of a bank's kind and status (ws21). The Bank
 * table is RLS-protected (a tenant sees only its own row), so the
 * implementation reads it as system in its own read-only transaction — a
 * registered {@code runAsSystem} call site (see {@link TenantContext}) —
 * and never exposes anything beyond the identifying columns below.
 *
 * <p>Entries are cached for a short TTL ({@code modocument.platform
 * .bank-cache-ttl}, default 30 s) so a suspension made on another instance
 * is seen within that bound; the instance that changes a bank calls
 * {@link #refresh(int)} after its commit so it sees the change at once.
 * Unknown banks are never cached.
 */
public interface BankDirectory {

    /** The identifying, non-content columns of one bank. */
    record BankRef(int bankId, String code, String name, BankKind kind, BankStatus status) {

        public boolean isPlatform() {
            return kind == BankKind.PLATFORM;
        }

        public boolean isSuspended() {
            return status == BankStatus.SUSPENDED;
        }
    }

    /** The bank, or empty when no bank has this id. */
    Optional<BankRef> find(int bankId);

    /**
     * The bank's kind. An unknown id answers {@link BankKind#CUSTOMER} — the
     * kind that grants nothing platform-level, so a lookup miss can never
     * widen anyone's privileges.
     */
    default BankKind kindOf(int bankId) {
        return find(bankId).map(BankRef::kind).orElse(BankKind.CUSTOMER);
    }

    /** Whether the bank is SUSPENDED (an unknown id is not). Cached for up to one TTL. */
    default boolean isSuspended(int bankId) {
        return find(bankId).map(BankRef::isSuspended).orElse(false);
    }

    /**
     * {@link #isSuspended} from a fresh read (the cached entry is dropped
     * first). For the rare, session-opening decisions — sign-in, MFA verify,
     * token refresh, OIDC login (ws21 suspension) — that must not trust an
     * entry written up to one TTL ago on another instance. Per-request token
     * validation uses the cached {@link #isSuspended}: a suspension also
     * bumps every user's TokenVersion, which kills live tokens immediately.
     */
    default boolean isSuspendedUncached(int bankId) {
        refresh(bankId);
        return isSuspended(bankId);
    }

    /** Drops the cached entry, so the next lookup reads the database again. */
    void refresh(int bankId);
}
