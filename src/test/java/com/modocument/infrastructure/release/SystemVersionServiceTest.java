package com.modocument.infrastructure.release;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ws24: what GET /api/system/version reports, from each metadata source. */
class SystemVersionServiceTest {

    @Test
    void reportsBuildGitFlywayAndEnvironment() {
        Properties build = new Properties();
        build.setProperty("version", "1.2.3");
        build.setProperty("time", "2026-10-10T12:00:00Z");
        Properties git = new Properties();
        git.setProperty("commit.id", "0123456789abcdef0123456789abcdef01234567");
        Flyway flyway = flywayAt("241");

        SystemVersionService service = new SystemVersionService(
                provider(BuildProperties.class, new BuildProperties(build)),
                provider(GitProperties.class, new GitProperties(git)),
                provider(Flyway.class, flyway),
                "staging");

        VersionInfo info = service.current();
        assertThat(info.version()).isEqualTo("1.2.3");
        assertThat(info.buildTime()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z").toString());
        assertThat(info.commit()).isEqualTo("0123456");
        assertThat(info.schemaVersion()).isEqualTo("241");
        assertThat(info.deployedAt()).isNull();
        assertThat(info.environment()).isEqualTo("staging");

        // The schema version is cached (re-read at most once a minute).
        service.current();
        verify(flyway, times(1)).info();
    }

    @Test
    void everyMissingSourceIsNullNotAnError() {
        Flyway broken = mock(Flyway.class);
        when(broken.info()).thenThrow(new IllegalStateException("database down"));

        VersionInfo bare = service(null, "").current();
        assertThat(bare.version()).isNull();
        assertThat(bare.commit()).isNull();
        assertThat(bare.buildTime()).isNull();
        assertThat(bare.schemaVersion()).isNull();
        assertThat(bare.environment()).isEqualTo("local");

        assertThat(service(broken, "production").current().schemaVersion()).isNull();
    }

    @Test
    void environmentIsAPlainNameOrUnknown() {
        assertThat(service(null, " production ").environment()).isEqualTo("production");
        assertThat(service(null, "prod<script>").environment()).isEqualTo("unknown");
        assertThat(service(null, "x".repeat(33)).environment()).isEqualTo("unknown");
    }

    private static SystemVersionService service(Flyway flyway, String environment) {
        return new SystemVersionService(provider(BuildProperties.class, null),
                provider(GitProperties.class, null), provider(Flyway.class, flyway), environment);
    }

    private static Flyway flywayAt(String version) {
        MigrationInfo current = mock(MigrationInfo.class);
        when(current.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        MigrationInfoService infos = mock(MigrationInfoService.class);
        when(infos.current()).thenReturn(current);
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenReturn(infos);
        return flyway;
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (bean != null) {
            factory.addBean(type.getSimpleName(), bean);
        }
        return factory.getBeanProvider(type);
    }
}
