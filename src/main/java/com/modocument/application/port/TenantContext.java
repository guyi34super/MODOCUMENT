package com.modocument.application.port;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The bank (tenant) the current thread is working for.
 *
 * <p>For web requests a servlet filter opens the context from the JWT's
 * bank_id claim and clears it in a finally block. Background work that acts
 * for a specific tenant wraps itself in {@link #runAs(int, Runnable)} (the
 * extraction worker does this with the job's bank); maintenance work that is
 * deliberately tenant-less uses {@link #runAsSystem(Runnable)}.
 *
 * <p>This is only the seam: nothing filters on it yet. The row-level-security
 * workstream will bind {@link #currentBankId()} to the database session so
 * cross-tenant reads become impossible at the SQL layer.
 */
public final class TenantContext {

    /** Sentinel stored by {@link #runAsSystem}; never a real BankId. */
    private static final int SYSTEM = Integer.MIN_VALUE;

    private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * The current tenant, or empty when unset, running as SYSTEM, or holding
     * a non-positive id (an unstamped legacy object).
     */
    public static Optional<Integer> currentBankId() {
        Integer bankId = CURRENT.get();
        return bankId == null || bankId <= 0 ? Optional.empty() : Optional.of(bankId);
    }

    public static boolean isSystem() {
        Integer bankId = CURRENT.get();
        return bankId != null && bankId == SYSTEM;
    }

    public static void runAs(int bankId, Runnable action) {
        Integer previous = CURRENT.get();
        CURRENT.set(bankId);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T runAs(int bankId, Callable<T> action) throws Exception {
        Integer previous = CURRENT.get();
        CURRENT.set(bankId);
        try {
            return action.call();
        } finally {
            restore(previous);
        }
    }

    public static void runAsSystem(Runnable action) {
        Integer previous = CURRENT.get();
        CURRENT.set(SYSTEM);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T runAsSystem(Callable<T> action) throws Exception {
        Integer previous = CURRENT.get();
        CURRENT.set(SYSTEM);
        try {
            return action.call();
        } finally {
            restore(previous);
        }
    }

    /**
     * Request-scoped entry point for the servlet filter; everything else
     * should prefer the runAs wrappers, which restore the previous value.
     */
    public static void open(int bankId) {
        CURRENT.set(bankId);
    }

    /** Clears the context (the filter's finally block). */
    public static void clear() {
        CURRENT.remove();
    }

    private static void restore(Integer previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
