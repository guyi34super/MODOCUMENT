package com.modocument.pgit;

import com.modocument.infrastructure.release.SystemVersionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws24 on real PostgreSQL: {@code GET /api/system/version}'s schemaVersion
 * is the newest migration Flyway applied to the database — read through the
 * Flyway API as the schema owner, exactly as the production context does
 * (the H2 suite has no Flyway and reports null).
 */
class SystemVersionPgIT extends PgItSupport {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    void schemaVersionIsTheNewestAppliedMigration() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(ownerDataSource())
                .locations("classpath:db/migration")
                .load();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("flyway", flyway);

        SystemVersionService service = new SystemVersionService(
                beans.getBeanProvider(BuildProperties.class),
                beans.getBeanProvider(GitProperties.class),
                beans.getBeanProvider(Flyway.class),
                "staging");

        assertThat(service.current().schemaVersion()).isEqualTo(newestMigrationOnDisk());
        assertThat(service.current().environment()).isEqualTo("staging");
    }

    private static String newestMigrationOnDisk() throws Exception {
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources", "db", "migration"))) {
            return String.valueOf(files
                    .map(path -> VERSIONED.matcher(path.getFileName().toString()))
                    .filter(Matcher::matches)
                    .mapToInt(m -> Integer.parseInt(m.group(1)))
                    .max()
                    .orElseThrow());
        }
    }
}
