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

    private final DatabaseExportService service = new DatabaseExportService();

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
    void rejectsEmptyTable() {
        assertThatThrownBy(() -> service.export(new TableData(List.of(), List.of()),
                request("jdbc:h2:mem:dbexport3", "People")))
                .isInstanceOf(IllegalStateException.class);
    }
}
