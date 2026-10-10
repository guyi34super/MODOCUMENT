package com.modocument.api.config;

import com.modocument.infrastructure.security.IdentityContextFilter;
import com.modocument.infrastructure.security.MfaChallengeScopeValidator;
import com.modocument.infrastructure.security.RevocationValidator;
import com.modocument.infrastructure.security.client.ApiClientAuthenticator;
import com.modocument.infrastructure.security.client.ClientAuthentication;
import com.modocument.infrastructure.security.client.ClientAuthenticationFilter;
import com.modocument.infrastructure.security.client.ClientTokenService;
import com.modocument.infrastructure.security.client.MtlsClientCertificates;
import com.modocument.infrastructure.security.oidc.OidcLoginFailureHandler;
import com.modocument.infrastructure.security.oidc.OidcLoginSuccessHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Stateless JWT security: /api/auth/login, /api/auth/refresh (cookie-driven
 * rotation), the health probe and the ws18 synthetic probe (GET
 * /api/probe/synthetic, rate-limited) are open, the dev tooling paths (Swagger, H2
 * console) only when modocument.security.permit-dev-paths is on (local/test
 * profiles), and everything else requires a Bearer token — including the
 * generated OpenAPI spec at /v3/api-docs, which exists in every profile but is
 * anonymous only in dev (ws17 decision, see OpenApiConfig). Registration is no
 * longer public — users are admin-created (bootstrap admin + future admin
 * endpoint). Every accepted token additionally passes the
 * {@link RevocationValidator} (jti denylist + TokenVersion check).
 *
 * <p><b>Machine callers (ws26).</b> Bank systems and Connector Agents
 * authenticate as API clients — an OAuth2 client token (same signing key,
 * {@code typ=client}, converted to a {@link ClientAuthentication}), an
 * {@code Authorization: ApiKey} header or a client certificate forwarded by
 * the trusted proxy (both handled by {@link ClientAuthenticationFilter},
 * placed before bearer authentication). The two worlds never mix:
 * <ul>
 *   <li>{@code /api/integration/v1/**} and {@code /api/agent/v1/**} admit
 *       ONLY a client ({@code ROLE_API_CLIENT}) — a user token is a 403;</li>
 *   <li>every other authenticated route admits ONLY a user — a client
 *       credential is a 403;</li>
 *   <li>public: {@code POST /api/integration/oauth/token} (the client
 *       authenticates inside) and {@code POST /api/agent/v1/enroll} (the
 *       one-time enrolment token is the credential, SE5).</li>
 * </ul>
 * The permission checks then narrow a client to its scopes
 * ({@code PermissionChecker}).
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
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            // ws07 OIDC front door: these beans exist ONLY when
            // modocument.oidc.providers is configured (OidcClientConfig);
            // without them oauth2Login stays off and this chain is unchanged.
            ObjectProvider<ClientRegistrationRepository> oidcRegistrations,
            ObjectProvider<OidcLoginSuccessHandler> oidcSuccessHandler,
            ObjectProvider<OidcLoginFailureHandler> oidcFailureHandler,
            // ws26 machine identity (API keys, client certificates).
            ApiClientAuthenticator apiClientAuthenticator,
            MtlsClientCertificates mtlsCertificates) throws Exception {
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
                        // DENY in production, matching frame-ancestors 'none'
                        // and the SPA's nginx set (E1); SAMEORIGIN only for
                        // the dev tooling (the H2 console renders in frames).
                        // Pinned by HeaderContractTest.
                        .frameOptions(f -> {
                            if (permitDevPaths) {
                                f.sameOrigin();
                            } else {
                                f.deny();
                            }
                        })
                        // X-Content-Type-Options: nosniff is on by default.
                        .contentSecurityPolicy(c -> c.policyDirectives(csp))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        // Emitted only over HTTPS; harmless on plain-HTTP local dev.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            // mfa/verify is the second phase of login: its
                            // credential is the challenge token in the body
                            // (rate-limited per IP/user like login itself).
                            .requestMatchers("/api/auth/login", "/api/auth/refresh",
                                    "/api/auth/mfa/verify").permitAll()
                            // ws07 OIDC: SSO discovery for the login page, the
                            // authorization-redirect entry points and the
                            // provider callback (Spring's own filters; inert
                            // 404s when no OIDC provider is configured).
                            .requestMatchers("/api/auth/providers").permitAll()
                            .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                            .requestMatchers("/actuator/health", "/error").permitAll()
                            // ws18 (SUP-S11): the synthetic probe for external
                            // uptime monitors — GET only, rate-limited per ip,
                            // answers a version + one db boolean, nothing else.
                            .requestMatchers(HttpMethod.GET, "/api/probe/synthetic").permitAll()
                            // ws26: the OAuth2 token endpoint authenticates
                            // the CLIENT itself (secret or certificate), and
                            // agent enrolment's credential is the one-time
                            // enrolment token in the body (SE5).
                            .requestMatchers(HttpMethod.POST, "/api/integration/oauth/token")
                            .permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/agent/v1/enroll").permitAll()
                            // ws26: machine routes admit machine callers only.
                            .requestMatchers("/api/integration/v1/**", "/api/agent/v1/**")
                            .hasAuthority(ClientAuthentication.AUTHORITY);
                    if (permitDevPaths) {
                        // Outside dev these fall through to authenticated():
                        // the generated spec is served to any valid token
                        // (ApiDocsExposureTest), never anonymously.
                        a.requestMatchers("/swagger/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/h2-console/**").permitAll();
                    }
                    // Everything else: an authenticated USER — never a client.
                    a.anyRequest().access(USER_ONLY);
                })
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(
                        SecurityConfig::convertJwt)))
                // ws26: API key / client certificate, before bearer tokens.
                .addFilterBefore(new ClientAuthenticationFilter(apiClientAuthenticator,
                        mtlsCertificates), BearerTokenAuthenticationFilter.class)
                // After bearer auth so the validated JWT's claims are in the
                // security context: opens TenantContext (bank_id) and the
                // userId/bankId/role logging MDC in one place; cleared in the
                // filter's finally block. (RequestIdFilter runs much earlier,
                // as a HIGHEST_PRECEDENCE servlet filter outside this chain.)
                .addFilterAfter(new IdentityContextFilter(), BearerTokenAuthenticationFilter.class);
        // ws07 OIDC front door (bridge pattern): external IdPs authenticate
        // the browser at /oauth2/authorization/{registrationId} ->
        // /login/oauth2/code/*, then the success handler issues MoDocument's
        // OWN refresh cookie and redirects into the SPA — the resource-server
        // side above keeps validating only our HS256 tokens. Configured only
        // when registrations exist; otherwise this chain is exactly as before.
        ClientRegistrationRepository registrations = oidcRegistrations.getIfAvailable();
        if (registrations != null) {
            http.oauth2Login(o -> o
                            .clientRegistrationRepository(registrations)
                            .successHandler(oidcSuccessHandler.getObject())
                            .failureHandler(oidcFailureHandler.getObject()))
                    // Keep the API's 401 contract: oauth2Login must not swap
                    // the Bearer entry point for a login-page redirect.
                    .exceptionHandling(e ->
                            e.authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint()));
        }
        return http.build();
    }

    /** Authenticated, and not a machine client (ws26). */
    private static final AuthorizationManager<RequestAuthorizationContext> USER_ONLY =
            AuthorizationManagers.allOf(AuthenticatedAuthorizationManager.authenticated(),
                    AuthorizationManagers.not(
                            AuthorityAuthorizationManager.hasAuthority(
                                    ClientAuthentication.AUTHORITY)));

    private static final Converter<Jwt, AbstractAuthenticationToken> USER_JWT_CONVERTER =
            new JwtAuthenticationConverter();

    /**
     * A validated JWT becomes a user's JwtAuthenticationToken (as before) or,
     * for a {@code typ=client} token, a {@link ClientAuthentication} — never
     * a Jwt principal, so no user code path can treat a machine as a user.
     */
    static AbstractAuthenticationToken convertJwt(Jwt jwt) {
        if (ClientTokenService.isClientToken(jwt)) {
            return new ClientAuthentication(ClientTokenService.principalOf(jwt), jwt.getId());
        }
        return USER_JWT_CONVERTER.convert(jwt);
    }

    /**
     * Argon2id (SEC-018) for every NEW hash, with BCrypt kept only for
     * matching legacy hashes: historical rows carry raw (un-prefixed) BCrypt,
     * which the defaultPasswordEncoderForMatches covers, and they are
     * re-hashed to "{argon2}..." on the next successful login
     * (upgradeEncoding — see AuthUseCase). Parameters are
     * spring-security 5.8+ defaults: salt 16 / hash 32 bytes, parallelism 1,
     * 16 MiB memory, 2 iterations.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        java.util.Map<String, PasswordEncoder> encoders = java.util.Map.of(
                "argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                "bcrypt", new BCryptPasswordEncoder());
        DelegatingPasswordEncoder delegating =
                new DelegatingPasswordEncoder("argon2", encoders);
        delegating.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return delegating;
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(RevocationValidator revocationValidator,
                                 MfaChallengeScopeValidator mfaChallengeScopeValidator) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        // Signature (the decoder), then exp/nbf, then the server-side
        // revocation checks (jti denylist, TokenVersion, user existence; the
        // ws26 client branch), then the MFA-challenge scope check (a
        // challenge token is not an access token — B3).
        // ws26: JwtValidators.createDefault() also adds Spring's RFC 8705
        // X509CertificateThumbprintValidator, which reads the certificate of
        // a TLS connection the app itself terminated — it never does (the
        // ALB does), so a certificate-bound client token could never pass.
        // The cnf binding is checked instead by RevocationValidator's client
        // branch against the certificate the trusted proxy forwarded; user
        // tokens carry no cnf, so for them nothing changes.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), revocationValidator, mfaChallengeScopeValidator));
        return decoder;
    }
}
