package com.modocument.infrastructure.extraction;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ws29: the default build carries no Mongo/Kafka client; scale settings must fail fast and clearly. */
class ScaleModeEnvironmentGuardTest {

    private static final ClassLoader LOADER = ScaleModeEnvironmentGuardTest.class.getClassLoader();

    @Test
    void theDefaultBuildShipsNeitherClient() {
        assertThat(ClassUtils.isPresent(ScaleModeEnvironmentGuard.MONGO_CLASS, LOADER)).isFalse();
        assertThat(ClassUtils.isPresent(ScaleModeEnvironmentGuard.KAFKA_CLASS, LOADER)).isFalse();
    }

    @Test
    void sqlAndMemoryStoresPass() {
        assertThatCode(() -> ScaleModeEnvironmentGuard.check(new MockEnvironment(), LOADER))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScaleModeEnvironmentGuard.check(new MockEnvironment()
                .withProperty("extraction.store", "sql")
                .withProperty("extraction.kafka.enabled", "false"), LOADER))
                .doesNotThrowAnyException();
    }

    @Test
    void mongoOrKafkaWithoutTheScaleBuildFailsWithTheFix() {
        assertThatThrownBy(() -> ScaleModeEnvironmentGuard.check(new MockEnvironment()
                .withProperty("extraction.store", "mongo"), LOADER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-Pscale");
        assertThatThrownBy(() -> ScaleModeEnvironmentGuard.check(new MockEnvironment()
                .withProperty("extraction.kafka.enabled", "true"), LOADER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka");
    }
}
