package com.modocument.api.security;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;

/**
 * Turns on method security for the {@code @RequiresPermission} layer. The
 * HTTP layer's {@code .anyRequest().authenticated()} (SecurityConfig) stays
 * the outer deny-by-default wall; this adds the per-route permission check on
 * top of it.
 *
 * <p>The {@link AnnotationTemplateExpressionDefaults} bean enables the
 * {@code {value}} placeholder in {@code @RequiresPermission}'s composed
 * {@code @PreAuthorize} expression.
 *
 * <p>The {@link AuthorizationEventPublisher} bean makes the method-security
 * interceptors publish {@code AuthorizationDeniedEvent}s (by default they
 * publish nothing), which {@code AuthorizationDeniedAuditListener} turns
 * into append-only AUTHORIZATION_DENIED audit rows (C7).
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    @Bean
    static AnnotationTemplateExpressionDefaults annotationTemplateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }

    @Bean
    static AuthorizationEventPublisher authorizationEventPublisher(
            ApplicationEventPublisher eventPublisher) {
        return new SpringAuthorizationEventPublisher(eventPublisher);
    }
}
