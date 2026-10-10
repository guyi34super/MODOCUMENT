package com.modocument.pgit;

import com.modocument.infrastructure.persistence.TenantAwareJpaTransactionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the H2/PostgreSQL schema-parity hole: the FULL Flyway chain runs
 * against a real PostgreSQL (in PgItSupport, as the owner) and then the real
 * application context boots on the {@code modocument_app} datasource with
 * {@code ddl-auto=validate} — Hibernate checks every entity mapping against
 * the migrated schema, something the H2 suite (ddl-auto=create, Flyway off)
 * can never do.
 */
class FlywayBootPgIT extends PgSpringItSupport {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void contextBootsOnTheAppRoleDatasourceWithValidatedMappings() throws Exception {
        // Reaching this line at all means ddl-auto=validate passed for every
        // entity; now pin down WHO the app is connected as.
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT current_user, current_setting('is_superuser')")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(APP_ROLE);
            assertThat(rs.getString(2)).isEqualTo("off");
        }
    }

    @Test
    void tenantAwareTransactionManagerIsThePrimaryOne() {
        assertThat(transactionManager).isInstanceOf(TenantAwareJpaTransactionManager.class);
    }

    @Test
    void migrationChainAppliedIncludingRoleAndRlsVersions() throws Exception {
        List<String> versions = new ArrayList<>();
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history "
                             + "WHERE success AND version IS NOT NULL")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        assertThat(versions)
                .as("baseline, role grants (V12), RLS (V13) and the workstream table "
                        + "migrations (V20, V70, V80, V90, V100, V120, ws18 V160 alert log "
                        + "and V161 audit-feed indexes) must all have applied")
                .contains("1", "10", "11", "12", "13", "15", "20", "70", "80", "90", "100",
                        "120", "160", "161");
    }
}
