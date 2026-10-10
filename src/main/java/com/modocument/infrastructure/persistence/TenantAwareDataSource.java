package com.modocument.infrastructure.persistence;

import com.modocument.application.port.TenantContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The second half of the {@code app.tenant} binding (the first is
 * {@link TenantAwareJpaTransactionManager}): stamps the current
 * {@link TenantContext} onto EVERY pooled connection checkout with
 * {@code set_config('app.tenant', ?, false)} — session scope, overwritten
 * unconditionally on the next checkout, so a value can never leak between
 * checkouts.
 *
 * <p>Why both: {@code @Transactional} work gets the SET LOCAL binding in
 * {@code doBegin}, but plenty of read paths legitimately run WITHOUT a
 * transaction — Spring Data derived query methods (ownership lookups such
 * as {@code findByTemplateIdAndOwnerUserId}) and plain {@code JdbcTemplate}
 * reads have no transaction for the manager to hook. Hibernate/the template
 * still checks a connection out for each of them, and this wrapper binds
 * the tenant right there. With neither binding a connection carries an
 * unset/empty tenant and the V13 policies return ZERO rows — fail closed,
 * never cross-tenant.
 *
 * <p>Non-PostgreSQL databases (the H2 local/test profiles) are detected on
 * the first checkout and the stamp becomes a no-op.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private volatile Boolean postgres;

    public TenantAwareDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return stamped(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return stamped(super.getConnection(username, password));
    }

    private Connection stamped(Connection connection) throws SQLException {
        if (Boolean.FALSE.equals(postgres)) {
            return connection;
        }
        try {
            Boolean isPostgres = postgres;
            if (isPostgres == null) {
                // Detected once per JVM; a cheap in-driver metadata call.
                isPostgres = "PostgreSQL".equalsIgnoreCase(
                        connection.getMetaData().getDatabaseProductName());
                postgres = isPostgres;
            }
            if (!isPostgres) {
                return connection;
            }
            String tenant = TenantContext.isSystem()
                    ? TenantAwareJpaTransactionManager.SYSTEM_WILDCARD
                    : TenantContext.currentBankId().map(String::valueOf).orElse("");
            // Parameterized on purpose — never splice the value into SQL.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT set_config('app.tenant', ?, false)")) {
                statement.setString(1, tenant);
                try (ResultSet ignored = statement.executeQuery()) {
                    // set_config returns the new value; nothing to read.
                }
            }
            return connection;
        } catch (SQLException | RuntimeException e) {
            // A connection whose tenant stamp failed must never reach the
            // application (it would carry the PREVIOUS checkout's tenant).
            try {
                connection.close();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }
}
