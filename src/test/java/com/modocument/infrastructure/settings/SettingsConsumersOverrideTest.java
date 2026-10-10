package com.modocument.infrastructure.settings;

import com.modocument.application.mapping.MappingProperties;
import com.modocument.application.mapping.MappingSettings;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.RefreshTokenRepository;
import com.modocument.application.port.RevokedTokenRepository;
import com.modocument.application.port.TenantContext;
import com.modocument.application.port.UserMfaRepository;
import com.modocument.application.privacy.MaskingPolicy;
import com.modocument.application.privacy.MaskingProperties;
import com.modocument.application.security.LoginLockoutPolicy;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.application.usecase.UserSecurityService;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.RefreshToken;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.doccreation.parse.AiSectionSplitter;
import com.modocument.infrastructure.alerting.AlertProperties;
import com.modocument.infrastructure.alerting.AlertRule;
import com.modocument.infrastructure.alerting.AlertRuleCatalogue;
import com.modocument.infrastructure.export.ExportCapExceededException;
import com.modocument.infrastructure.export.ExportCapProperties;
import com.modocument.infrastructure.export.ExportGuard;
import com.modocument.infrastructure.file.ParsingProperties;
import com.modocument.infrastructure.file.ParsingSettings;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.ratelimit.ApiRateLimitInterceptor;
import com.modocument.infrastructure.ratelimit.ApiRateLimitProperties;
import com.modocument.infrastructure.ratelimit.AuthRateLimiter;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import com.modocument.infrastructure.ratelimit.MfaRateLimiter;
import com.modocument.infrastructure.ratelimit.TooManyRequestsException;
import com.modocument.infrastructure.retention.BankPurgeSummary;
import com.modocument.infrastructure.retention.RetentionProperties;
import com.modocument.infrastructure.retention.RetentionPurgeJob;
import com.modocument.infrastructure.retention.RetentionPurger;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.infrastructure.security.MfaChallengeTokens;
import com.modocument.testsupport.FixedUser;
import com.modocument.testsupport.InMemorySettings;
import com.modocument.testsupport.StubBankDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ws20 Wave 2: a STORED setting changes its consumer's behaviour — for the
 * overriding bank only (bank override), or for every bank without its own
 * value (platform value) — through the real resolution (in-memory store).
 * The no-override half of the contract is SettingsConsumersParityTest.
 */
class SettingsConsumersOverrideTest {

    private static final int BANK_A = 10;
    private static final int BANK_B = 20;
    private static final int BANK_C = 30;

    private final InMemorySettings settings = InMemorySettings.shipped();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------- passwords

    @Test
    void aBankOverrideOfThePasswordMinimumAppliesToThatBankOnly() throws Exception {
        PasswordPolicy policy = new PasswordPolicy(12, settings.reader());
        String thirteen = "Kestrel-Rotor"; // 13 characters, not breached
        settings.bank(BANK_A, SettingKeys.PASSWORD_MIN_LENGTH, "16");

        assertThat(policy.minLength(BANK_A)).isEqualTo(16);
        assertThat(policy.minLength(BANK_B)).isEqualTo(12);
        assertThatThrownBy(() -> policy.validate(thirteen, "x@a.example", BANK_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 16");
        policy.validate(thirteen, "x@b.example", BANK_B);

        // The two-argument form follows the caller's bank (TenantContext) ...
        TenantContext.runAs(BANK_A, () -> assertThatThrownBy(
                () -> policy.validate(thirteen, "x@a.example"))
                .isInstanceOf(IllegalArgumentException.class));
        TenantContext.runAs(BANK_B, () -> policy.validate(thirteen, "x@b.example"));
        // ... and the platform layer without one (bootstrap).
        policy.validate(thirteen, "boot@platform.example");
    }

    @Test
    void aPlatformValueAppliesToEveryBankWithoutItsOwn() throws Exception {
        PasswordPolicy policy = new PasswordPolicy(12, settings.reader());
        settings.platform(SettingKeys.PASSWORD_MIN_LENGTH, "14")
                .bank(BANK_A, SettingKeys.PASSWORD_MIN_LENGTH, "18");

        assertThat(policy.minLength(BANK_A)).as("own override wins").isEqualTo(18);
        assertThat(policy.minLength(BANK_B)).isEqualTo(14);
        assertThat(policy.minLength(BANK_C)).isEqualTo(14);
        assertThat(policy.minLength()).as("no tenant: platform layer").isEqualTo(14);
    }

    @Test
    void aStoredValueBelowTheHardFloorIsClampedNotObeyed() throws Exception {
        PasswordPolicy policy = new PasswordPolicy(12, settings.reader());
        settings.bank(BANK_A, SettingKeys.PASSWORD_MIN_LENGTH, "4");
        assertThat(policy.minLength(BANK_A)).isEqualTo(12);
    }

    // ------------------------------------------------------------ lockout

    @Test
    void aBankOverrideOfTheLockoutAllowanceLocksThatBanksUsersSooner() throws Exception {
        LoginLockoutPolicy policy = new LoginLockoutPolicy(5, 1, 30, settings.reader());
        settings.bank(BANK_A, SettingKeys.LOCKOUT_ALLOWED_ATTEMPTS, "3")
                .bank(BANK_A, SettingKeys.LOCKOUT_BASE_MINUTES, "5");
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 9, 0);
        AppUser inA = user(1, BANK_A, Role.READ_WRITE);
        AppUser inB = user(2, BANK_B, Role.READ_WRITE);

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(policy.registerFailure(inA, now)).isFalse();
            assertThat(policy.registerFailure(inB, now)).isFalse();
        }
        assertThat(policy.registerFailure(inA, now)).as("A: 4th failure locks").isTrue();
        assertThat(inA.getLockedUntil()).isEqualTo(now.plusMinutes(5));
        assertThat(policy.registerFailure(inB, now)).as("B: still within 5").isFalse();
        assertThat(policy.registerFailure(inB, now)).isFalse();
        assertThat(policy.registerFailure(inB, now)).as("B: 6th failure locks").isTrue();
        assertThat(inB.getLockedUntil()).isEqualTo(now.plusMinutes(1));
    }

    // --------------------------------------------------------- MFA, session

    @Test
    void mfaForAllRolesIsPerBankAndThePlatformAdminAlwaysOwesIt() throws Exception {
        AuthUseCase auth = authUseCase();
        settings.bank(BANK_A, SettingKeys.MFA_REQUIRE_FOR_ALL_ROLES, "true");

        assertThat(auth.mustEnrolMfa(user(1, BANK_A, Role.READ_ONLY))).isTrue();
        assertThat(auth.mustEnrolMfa(user(2, BANK_B, Role.READ_ONLY))).isFalse();
        assertThat(auth.mustEnrolMfa(user(3, BANK_B, Role.ADMIN))).as("locked on").isTrue();
        assertThat(auth.mustEnrolMfa(user(4, BANK_B, Role.PLATFORM_ADMIN))).isTrue();

        // A stored "false" for the locked admin switch is not an allowed value.
        settings.bank(BANK_B, SettingKeys.MFA_REQUIRE_FOR_ADMINS, "false");
        assertThat(auth.mustEnrolMfa(user(3, BANK_B, Role.ADMIN))).isTrue();
    }

    @Test
    void theRefreshIdleTimeoutIsTheTokenOwnersBankSetting() throws Exception {
        AppUserRepository users = mock(AppUserRepository.class);
        RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
        JwtService jwt = mock(JwtService.class);
        AuthUseCase auth = authUseCase(users, refreshTokens, jwt);
        settings.bank(BANK_A, SettingKeys.SESSION_IDLE_TIMEOUT, "30m");
        AppUser inA = user(1, BANK_A, Role.READ_WRITE);
        AppUser inB = user(2, BANK_B, Role.READ_WRITE);
        when(users.getById(1)).thenReturn(Optional.of(inA));
        when(users.getById(2)).thenReturn(Optional.of(inB));
        when(jwt.issueToken(any())).thenReturn("access");

        assertThat(auth.refreshIdleTimeout(BANK_A)).isEqualTo(Duration.ofMinutes(30));
        assertThat(auth.refreshIdleTimeout(BANK_B)).isEqualTo(Duration.ofHours(12));
        // Both tokens last used an hour ago: A's session is over, B's is not.
        String tokenA = token(refreshTokens, 1, Instant.now().minus(1, ChronoUnit.HOURS));
        String tokenB = token(refreshTokens, 2, Instant.now().minus(1, ChronoUnit.HOURS));
        assertThatThrownBy(() -> auth.refresh(tokenA, "198.51.100.1", "ua"))
                .isInstanceOf(AuthUseCase.BadCredentialsException.class)
                .hasMessageContaining("expired");
        assertThat(auth.refresh(tokenB, "198.51.100.1", "ua").refreshToken()).isNotBlank();
    }

    @Test
    void theAccessTokenLifetimeIsAPlatformSetting() throws Exception {
        JwtEncoder encoder = mock(JwtEncoder.class);
        JwtService service = new JwtService(encoder, 15, 0, settings.reader());
        assertThat(service.ttlMinutes()).isEqualTo(15);
        settings.platform(SettingKeys.JWT_TTL_MINUTES, "5");
        assertThat(service.ttlMinutes()).isEqualTo(5);
        // A bank cannot store a platform-scope setting: ignored.
        settings.clear().bank(BANK_A, SettingKeys.JWT_TTL_MINUTES, "6");
        assertThat(service.ttlMinutes()).isEqualTo(15);
        // An explicit legacy ttl-hours pins the lifetime outside the settings.
        assertThat(new JwtService(encoder, 15, 2, settings.reader()).ttlMinutes()).isEqualTo(120);
    }

    // -------------------------------------------------------- rate limits

    @Test
    void aBankOverrideOfARateLimitBucketMetersThatBanksUsersOnly() throws Exception {
        ApiRateLimitInterceptor interceptor = new ApiRateLimitInterceptor(
                currentUser(), new ApiRateLimitProperties(), settings.reader());
        settings.bank(BANK_A, SettingKeys.RATELIMIT_UPLOADS, "2")
                .platform(SettingKeys.RATELIMIT_ADMIN_SECURITY, "3");

        assertThat(interceptor.limitFor("uploads", BANK_A)).isEqualTo(2);
        assertThat(interceptor.limitFor("uploads", BANK_B)).isEqualTo(20);
        assertThat(interceptor.limitFor("admin-security", BANK_B)).as("platform value")
                .isEqualTo(3);
        assertThat(interceptor.settingKeys()).containsEntry("admin-security",
                SettingKeys.RATELIMIT_ADMIN_SECURITY);

        for (int call = 1; call <= 2; call++) {
            assertThat(upload(interceptor, 101, BANK_A)).isTrue();
            assertThat(upload(interceptor, 201, BANK_B)).isTrue();
        }
        assertThatThrownBy(() -> upload(interceptor, 101, BANK_A))
                .isInstanceOf(TooManyRequestsException.class);
        assertThat(upload(interceptor, 201, BANK_B)).as("B keeps the yml 20").isTrue();
    }

    @Test
    void theLlmQuotaIsPerBankAndTryCheckNeverThrows() throws Exception {
        LlmRateLimiter limiter = new LlmRateLimiter(60, 3_600_000, 1000, settings.reader());
        settings.bank(BANK_A, SettingKeys.RATELIMIT_LLM_PER_USER, "2");

        assertThat(limiter.limitFor(BANK_A)).isEqualTo(2);
        assertThat(limiter.limitFor(BANK_B)).isEqualTo(60);
        assertThat(limiter.tryCheck(1, BANK_A)).isTrue();
        assertThat(limiter.tryCheck(1, BANK_A)).isTrue();
        assertThat(limiter.tryCheck(1, BANK_A)).isFalse();
        assertThatThrownBy(() -> limiter.check(1, BANK_A))
                .isInstanceOf(TooManyRequestsException.class);
        // The thread's bank drives the one-argument forms.
        TenantContext.runAs(BANK_A, () -> assertThat(limiter.tryCheck(1)).isFalse());
        TenantContext.runAs(BANK_B, () -> {
            assertThat(limiter.tryCheck(2)).isTrue();
            limiter.check(2);
        });
    }

    @Test
    void authAndMfaLimitsArePlatformSettings() throws Exception {
        AuthRateLimiter auth = new AuthRateLimiter(30, 10, 900_000, 1000, settings.reader());
        MfaRateLimiter mfa = new MfaRateLimiter(30, 5, 60_000, 1000, settings.reader());
        settings.platform(SettingKeys.RATELIMIT_AUTH_PER_EMAIL, "2")
                .platform(SettingKeys.RATELIMIT_MFA_PER_USER, "2");

        assertThat(auth.perEmailMax()).isEqualTo(2);
        assertThat(auth.perIpMax()).isEqualTo(30);
        auth.check("203.0.113.1", "a@bank.example");
        auth.check("203.0.113.2", "a@bank.example");
        assertThatThrownBy(() -> auth.check("203.0.113.3", "a@bank.example"))
                .isInstanceOf(TooManyRequestsException.class);

        assertThat(mfa.perUserMax()).isEqualTo(2);
        mfa.checkUser("7");
        mfa.checkUser("7");
        assertThatThrownBy(() -> mfa.checkUser("7")).isInstanceOf(TooManyRequestsException.class);
    }

    // ---------------------------------------------------- data protection

    @Test
    void aBankOverrideOfTheExportCapAppliesToThatBankOnly() throws Exception {
        AuditRecorder audit = mock(AuditRecorder.class);
        ExportGuard guard = new ExportGuard(new ExportCapProperties(), audit,
                new FixedUser(7, BANK_A, Role.READ_WRITE), settings.reader());
        settings.bank(BANK_A, SettingKeys.EXPORT_CAP_READ_WRITE, "100");

        assertThat(guard.capFor(Role.READ_WRITE, BANK_A)).isEqualTo(100);
        assertThat(guard.capFor(Role.READ_WRITE, BANK_B)).isEqualTo(5_000);
        assertThat(guard.capFor(Role.ADMIN, BANK_A)).isEqualTo(50_000);
        assertThat(guard.capFor(Role.READ_ONLY, BANK_A)).as("yml, fail closed").isZero();

        ExportGuard.Attempt attempt = new ExportGuard.Attempt(11, "file", "CSV", 101, null);
        TenantContext.runAs(BANK_A, () -> assertThatThrownBy(() -> guard.authorize(attempt))
                .isInstanceOf(ExportCapExceededException.class));
        TenantContext.runAs(BANK_B, () ->
                assertThat(guard.authorize(attempt).overCap()).isFalse());
    }

    @Test
    void retentionCutoffIsComputedPerBankInsideTheSweep() throws Exception {
        RetentionPurger purger = mock(RetentionPurger.class);
        BankJpaRepository banks = mock(BankJpaRepository.class);
        when(banks.findAll()).thenReturn(List.of(bank(BANK_A), bank(BANK_B)));
        List<Integer> daysSeen = new ArrayList<>();
        when(purger.purgeBank(anyInt(), any(), any(), anyInt())).thenAnswer(call -> {
            int bankId = call.getArgument(0);
            LocalDateTime cutoff = call.getArgument(1);
            int days = call.getArgument(3);
            daysSeen.add(days);
            assertThat(TenantContext.currentBankId()).contains(bankId);
            assertThat(Duration.between(cutoff, LocalDateTime.now()).toDays()).isEqualTo(days);
            return new BankPurgeSummary(bankId, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        });
        RetentionProperties properties = new RetentionProperties(true, 30, settings.reader());
        settings.bank(BANK_A, SettingKeys.RETENTION_CUSTOMER_DATA_DAYS, "7")
                // Stored above the gate C5 ceiling (e.g. by hand): clamped to 30.
                .bank(BANK_B, SettingKeys.RETENTION_CUSTOMER_DATA_DAYS, "45");

        new RetentionPurgeJob(properties, purger, banks).runOnce();

        assertThat(daysSeen).containsExactly(7, 30);
        verify(purger).purgeBank(eq(BANK_A), any(), any(), eq(7));
        verify(purger).purgeBank(eq(BANK_B), any(), any(), eq(30));
        TenantContext.runAs(BANK_A, () -> assertThat(properties.customerDataDays()).isEqualTo(7));
    }

    @Test
    void screenWorkingMaskingIsPerBank() throws Exception {
        MaskingPolicy policy = new MaskingPolicy(new MaskingProperties(), settings.reader());
        settings.bank(BANK_A, SettingKeys.MASKING_SCREEN_WORKING, "true");

        TenantContext.runAs(BANK_A, () -> assertThat(
                policy.mask(MaskingPolicy.Channel.SCREEN_WORKING, Role.READ_WRITE)).isTrue());
        TenantContext.runAs(BANK_B, () -> assertThat(
                policy.mask(MaskingPolicy.Channel.SCREEN_WORKING, Role.READ_WRITE)).isFalse());
        assertThat(policy.screenWorkingMasked(BANK_A)).isTrue();
        // The fixed channels never move.
        TenantContext.runAs(BANK_B, () -> assertThat(
                policy.mask(MaskingPolicy.Channel.EXPORT, Role.ADMIN)).isTrue());
    }

    // ------------------------------------------------------ AI & extraction

    @Test
    void parsingAndMappingCopiesCarryTheBanksValuesAndAreFresh() throws Exception {
        ParsingProperties parsingDefaults = new ParsingProperties();
        ParsingSettings parsing = new ParsingSettings(parsingDefaults, settings.reader());
        MappingProperties mappingDefaults = new MappingProperties();
        mappingDefaults.setAliases(new java.util.LinkedHashMap<>(Map.of("matricule", "employeeno")));
        MappingSettings mapping = new MappingSettings(mappingDefaults, settings.reader());
        settings.bank(BANK_A, SettingKeys.PARSING_DETERMINISTIC_ENABLED, "false")
                .bank(BANK_A, SettingKeys.PARSING_EXCEL_LLM_ENABLED, "true")
                .platform(SettingKeys.PARSING_LLM_BATCH_SIZE, "25")
                .bank(BANK_A, SettingKeys.MAPPING_CONFIDENCE_THRESHOLD, "0.85")
                .bank(BANK_A, SettingKeys.MAPPING_LLM_FALLBACK_ENABLED, "false");

        ParsingProperties forA = parsing.forBank(BANK_A);
        ParsingProperties forB = parsing.forBank(BANK_B);
        assertThat(forA.isDeterministicEnabled()).isFalse();
        assertThat(forB.isDeterministicEnabled()).isTrue();
        assertThat(forA.getLlmBatchSize()).isEqualTo(25);
        assertThat(forB.getLlmBatchSize()).as("platform-wide").isEqualTo(25);
        assertThat(forA.getLlmSinglePassMaxRows()).isEqualTo(parsingDefaults.getLlmSinglePassMaxRows());
        assertThat(parsing.excelForBank(BANK_A).isLlmEnabled()).isTrue();
        assertThat(parsing.excelForBank(BANK_B).isLlmEnabled()).isFalse();
        assertThat(forA).isNotSameAs(parsing.forBank(BANK_A)).isNotSameAs(parsingDefaults);
        assertThat(parsingDefaults.getLlmBatchSize()).as("yml bean untouched").isEqualTo(50);

        MappingProperties mapA = mapping.forBank(BANK_A);
        assertThat(mapA.getConfidenceThreshold()).isEqualTo(0.85);
        assertThat(mapA.isLlmFallbackEnabled()).isFalse();
        assertThat(mapping.forBank(BANK_B).getConfidenceThreshold()).isEqualTo(0.7);
        assertThat(mapA.getAliases()).isEqualTo(mappingDefaults.getAliases())
                .isNotSameAs(mappingDefaults.getAliases());
        TenantContext.runAs(BANK_A, () ->
                assertThat(mapping.forCurrentBank().getConfidenceThreshold()).isEqualTo(0.85));
    }

    @Test
    void doccreationAiFollowsTheBankToggleAndTheAiMasterSwitch() throws Exception {
        AiSectionSplitter splitter = new AiSectionSplitter(null, null, null, null, true, "",
                settings.reader());
        settings.bank(BANK_A, SettingKeys.DOCCREATION_SECTION_AI_ENABLED, "false")
                .bank(BANK_B, SettingKeys.AI_ENABLED, "false");

        TenantContext.runAs(BANK_A, () -> assertThat(splitter.enabled()).isFalse());
        TenantContext.runAs(BANK_B, () -> assertThat(splitter.enabled()).isFalse());
        TenantContext.runAs(BANK_C, () -> assertThat(splitter.enabled()).isTrue());
    }

    // ------------------------------------------------------------- alerts

    @Test
    void storedPlatformTuningOverlaysTheWindowedRuleOnRebuild() throws Exception {
        AlertRuleCatalogue catalogue = new AlertRuleCatalogue(shippedAlerts(),
                settings.catalogue(), settings.resolver());
        assertThat(rule(catalogue, "export-burst").threshold()).isEqualTo(5);

        settings.platform(SettingKeys.ALERT_EXPORT_BURST_THRESHOLD, "12")
                .platform(SettingKeys.ALERT_EXPORT_BURST_WINDOW, "30m")
                .platform(SettingKeys.ALERT_PARSE_FALLBACK_SURGE_THRESHOLD, "40");
        assertThat(rule(catalogue, "export-burst").threshold())
                .as("unchanged until the settings-changed event").isEqualTo(5);
        catalogue.onSettingsChanged(
                new com.modocument.domain.model.settings.SettingsChangedEvent(2));

        AlertRule exportBurst = rule(catalogue, "export-burst");
        assertThat(exportBurst.threshold()).isEqualTo(12);
        assertThat(exportBurst.window()).isEqualTo(Duration.ofMinutes(30));
        assertThat(rule(catalogue, "parse-fallback-surge").threshold()).isEqualTo(40);
        assertThat(catalogue.windowed()).contains(exportBurst);
        assertThat(rule(catalogue, "login-failures-per-user").threshold()).isEqualTo(5);

        settings.clear();
        catalogue.rebuild();
        assertThat(rule(catalogue, "export-burst").threshold()).isEqualTo(5);
        assertThat(rule(catalogue, "export-burst").window()).isEqualTo(Duration.ofMinutes(10));
    }

    // ------------------------------------------------------------ helpers

    private static AlertRule rule(AlertRuleCatalogue catalogue, String name) {
        return catalogue.all().stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow();
    }

    static AlertProperties shippedAlerts() throws Exception {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(yaml.get(0));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("modocument.alerts", Bindable.of(AlertProperties.class))
                .orElseThrow(() -> new AssertionError("modocument.alerts missing"));
    }

    private AuthUseCase authUseCase() {
        return authUseCase(mock(AppUserRepository.class), mock(RefreshTokenRepository.class),
                mock(JwtService.class));
    }

    private AuthUseCase authUseCase(AppUserRepository users, RefreshTokenRepository refresh,
                                    JwtService jwt) {
        AuditRecorder audit = mock(AuditRecorder.class);
        return new AuthUseCase(users, new BCryptPasswordEncoder(4),
                new PasswordPolicy(12, settings.reader()),
                new LoginLockoutPolicy(5, 1, 30, settings.reader()),
                mock(UserMfaRepository.class), mock(MfaChallengeTokens.class), jwt, refresh,
                mock(RevokedTokenRepository.class), new UserSecurityService(users, audit), audit,
                new StubBankDirectory(), true, Duration.ofHours(12), settings.reader());
    }

    private static String token(RefreshTokenRepository repository, int userId, Instant issuedAt) {
        String value = "refresh-" + UUID.randomUUID();
        RefreshToken token = new RefreshToken();
        token.setJti(UUID.randomUUID());
        token.setUserId(userId);
        token.setFamilyId(UUID.randomUUID());
        token.setTokenHash(Hashing.sha256(value));
        token.setIssuedAt(issuedAt);
        token.setExpiresAt(Instant.now().plus(5, ChronoUnit.DAYS));
        when(repository.getByTokenHash(Hashing.sha256(value))).thenReturn(Optional.of(token));
        return value;
    }

    private static AppUser user(int id, int bankId, Role role) {
        AppUser user = new AppUser(id, "u" + id + "@bank.example", "hash", "User " + id);
        user.setBankId(bankId);
        user.setRole(role);
        return user;
    }

    private static Bank bank(int id) {
        Bank bank = new Bank("B" + id, "Bank " + id);
        bank.setBankId(id);
        return bank;
    }

    /** The user id per request comes from this thread-local, like the JWT subject. */
    private static final ThreadLocal<Integer> CALLER = new ThreadLocal<>();

    private static com.modocument.application.port.CurrentUserProvider currentUser() {
        return CALLER::get;
    }

    private static boolean upload(ApiRateLimitInterceptor interceptor, int userId, int bankId)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/fileupload/upload");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/fileupload/upload");
        HandlerMethod handler = new HandlerMethod(new Object(),
                Object.class.getMethod("toString"));
        CALLER.set(userId);
        try {
            return TenantContext.runAs(bankId, () ->
                    interceptor.preHandle(request, new MockHttpServletResponse(), handler));
        } finally {
            CALLER.remove();
        }
    }
}
