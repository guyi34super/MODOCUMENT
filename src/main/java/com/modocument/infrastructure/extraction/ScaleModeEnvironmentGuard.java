package com.modocument.infrastructure.extraction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;

/**
 * Fails startup with a clear message when scale-mode extraction
 * ({@code extraction.store=mongo} or {@code extraction.kafka.enabled=true},
 * e.g. SPRING_PROFILES_ACTIVE=scale) is configured on a jar built WITHOUT
 * the Maven {@code scale} profile (ws29): the default build ships neither
 * the MongoDB nor the Kafka client, so the scale adapters are not in it and
 * the context would otherwise die later on an opaque missing-bean error.
 * Registered in META-INF/spring.factories.
 */
public class ScaleModeEnvironmentGuard implements EnvironmentPostProcessor {

    static final String MONGO_CLASS = "org.springframework.data.mongodb.core.MongoTemplate";
    static final String KAFKA_CLASS = "org.springframework.kafka.core.KafkaTemplate";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ClassLoader loader = application == null ? null : application.getClassLoader();
        check(environment, loader);
    }

    static void check(ConfigurableEnvironment environment, ClassLoader loader) {
        boolean mongo = "mongo".equalsIgnoreCase(environment.getProperty("extraction.store", "").strip());
        boolean kafka = "true".equalsIgnoreCase(environment.getProperty("extraction.kafka.enabled", "").strip());
        if (mongo && !ClassUtils.isPresent(MONGO_CLASS, loader)) {
            throw new IllegalStateException(missing("extraction.store=mongo", "MongoDB"));
        }
        if (kafka && !ClassUtils.isPresent(KAFKA_CLASS, loader)) {
            throw new IllegalStateException(missing("extraction.kafka.enabled=true", "Kafka"));
        }
    }

    private static String missing(String setting, String what) {
        return setting + " (scale mode) needs a build with the Maven 'scale' profile "
                + "(mvn -Pscale package): this jar has no " + what + " client. Use extraction.store=sql "
                + "(the default deployment) or rebuild with -Pscale.";
    }
}
