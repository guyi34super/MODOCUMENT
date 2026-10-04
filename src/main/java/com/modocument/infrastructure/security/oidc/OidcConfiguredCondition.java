package com.modocument.infrastructure.security.oidc;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * True only when at least one {@code modocument.oidc.providers.*} entry is
 * bound in the environment. Guarding the whole {@link OidcClientConfig} with
 * this keeps the OIDC machinery entirely absent (no
 * ClientRegistrationRepository bean, no oauth2Login filter chain additions)
 * on unconfigured deployments and in the main test suite — a plain
 * {@code @ConditionalOnProperty} cannot express "this map is non-empty".
 */
class OidcConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> providers = Binder.get(context.getEnvironment())
                .bind("modocument.oidc.providers",
                        Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of());
        return !providers.isEmpty();
    }
}
