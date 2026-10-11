package com.modocument.testsupport;

import com.modocument.infrastructure.export.DatabaseExportService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.sql.DriverManager;

/**
 * ws28: the database export only takes a STRUCTURED target now (vendor,
 * host, database...). H2-suite tests that push rows to a "database" import
 * this: the export's connection opener lands in an in-memory H2 database
 * named after the request's {@code database} ({@code jdbc:h2:mem:<database>}),
 * whatever vendor and host the request names — the URL building, egress
 * check and timeouts stay the production ones (DatabaseExportServiceTest
 * pins them).
 */
@TestConfiguration
public class H2ExportTargets {

    /** The H2 URL a test reads back the exported rows from. */
    public static String h2Url(String database) {
        return "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1";
    }

    @Bean
    DatabaseExportService.ConnectionOpener h2ExportOpener() {
        return (dialect, url, properties) -> {
            String database = url.substring(url.lastIndexOf('/') + 1).split("[?;]")[0];
            return DriverManager.getConnection(h2Url(database));
        };
    }
}
