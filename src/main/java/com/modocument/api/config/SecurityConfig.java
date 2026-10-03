package com.modocument.api.config;

import com.modocument.infrastructure.security.RevocationValidator;
import com.modocument.infrastructure.security.TenantContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Stateless JWT security: /api/auth/login, /api/auth/refresh (cookie-driven
 * rotation) and the health probe are open, the dev tooling paths (Swagger, H2
 * console) only when modocument.security.permit-dev-paths is on (local/test
 * profiles), and everything else requires a Bearer token. Registration is no
 * longer public — users are admin-created (bootstrap admin + future admin
 * endpoint). Every accepted token additionally passes the
 * {@link RevocationValidator} (jti denylist + TokenVersion check).
 */
@Configuration
public class SecurityConfig {

    /** The old baked-in dev fallback; must never be used as a real secret. */
    private static final String LEGACY_DEV_SECRET =
            "modocument-local-dev-secret-change-in-production-0123456789";

    private final SecretKey secretKey;
    private final boolean permitDevPaths;

    public SecurityConfig(@Value("${modocument.security.jwt.secret}") String secret,
                          @Value("${modocument.security.permit-dev-paths:false}") boolean permitDevPaths) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "MODOCUMENT_JWT_SECRET is not set. Generate a signing key of at least "
                            + "32 bytes (e.g. `openssl rand -base64 48`) and set it in the "
                            + "environment before starting the app.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "MODOCUMENT_JWT_SECRET is too short: HS256 needs at least 32 bytes.");
        }
        if (!permitDevPaths && LEGACY_DEV_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "MODOCUMENT_JWT_SECRET is set to the publicly-known development default. "
                            + "Generate a real key (e.g. `openssl rand -base64 48`).");
        }
        this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.permitDevPaths = permitDevPaths;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // A JSON API serves no markup, so the strictest CSP applies; when the
        // dev tooling (Swagger UI, H2 console) is enabled, relax it just enough
        // for those pages to load their own assets.
        String csp = permitDevPaths
                ? "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self' 'unsafe-inline'; frame-ancestors 'self'"
                : "default-src 'none'; frame-ancestors 'none'";
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .frameOptions(f -> f.sameOrigin())
                        // X-Content-Type-Options: nosniff is on by default.
                        .contentSecurityPolicy(c -> c.policyDirectives(csp))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        // Emitted only over HTTPS; harmless on plain-HTTP local dev.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                            .requestMatchers("/actuator/health", "/error").permitAll();
                    if (permitDevPaths) {
                        a.requestMatchers("/swagger/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/h2-console/**").permitAll();
                    }
                    a.anyRequest().authenticated();
                })
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                // After bearer auth so the validated JWT's bank_id claim is in
                // the security context; cleared in the filter's finally block.
                .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(RevocationValidator revocationValidator) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        // Signature/exp checks first (createDefault), then the server-side
        // revocation checks (jti denylist, TokenVersion, user existence).
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), revocationValidator));
        return decoder;
    }
}
