package com.modocument.infrastructure.persistence;

import com.modocument.application.port.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * The runtime half of row-level security: binds the current
 * {@link TenantContext} to the PostgreSQL {@code app.tenant} setting at the
 * start of EVERY transaction, on the transaction's own connection, with
 * {@code set_config(..., true)} — SET LOCAL semantics, so the value dies
 * with the commit/rollback and can never leak to another pooled checkout.
 * The V13 policies filter every BankId-stamped table by that setting when
 * the connection belongs to the {@code modocument_app} role (V12).
 *
 * <p>Mapping: a real tenant binds its bankId; {@link TenantContext#isSystem()
 * SYSTEM} maintenance work binds the {@code '*'} wildcard; NO context binds
 * the empty string, which matches nothing — FAIL CLOSED. A transaction that
 * forgets to establish a tenant reads zero rows instead of all of them.
 *
 * <p>{@link TenantAwareDataSource} is the complement: it binds the tenant at
 * every connection CHECKOUT (session scope), covering the non-transactional
 * read paths this manager never sees; the SET LOCAL stamp here additionally
 * guarantees a transaction is bound to the tenant current at ITS start even
 * when Hibernate holds one connection across transaction boundaries.
 *
 * <p>On anything but PostgreSQL (the H2 local/test profiles) this detects
 * the database once and degrades to a plain {@link JpaTransactionManager}.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    /** Matched by the V13 policies: {@code current_setting('app.tenant', true) = '*'}. */
    static final String SYSTEM_WILDCARD = "*";

    private volatile Boolean postgres;

    public TenantAwareJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        if (Boolean.FALSE.equals(postgres)) {
            return;
        }
        EntityManagerHolder holder = (EntityManagerHolder)
                TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (holder == null) {
            return;
        }
        Session session = holder.getEntityManager().unwrap(Session.class);
        session.doWork(connection -> {
            Boolean isPostgres = postgres;
            if (isPostgres == null) {
                // Detected once per JVM; a cheap in-driver metadata call.
                isPostgres = "PostgreSQL".equalsIgnoreCase(
                        connection.getMetaData().getDatabaseProductName());
                postgres = isPostgres;
            }
            if (!isPostgres) {
                return;
            }
            String tenant = TenantContext.isSystem()
                    ? SYSTEM_WILDCARD
                    : TenantContext.currentBankId().map(String::valueOf).orElse("");
            // Parameterized on purpose — the value comes from a thread local
            // that request filters populate; never splice it into SQL.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT set_config('app.tenant', ?, true)")) {
                statement.setString(1, tenant);
                try (ResultSet ignored = statement.executeQuery()) {
                    // set_config returns the new value; nothing to read.
                }
            }
        });
    }
}
