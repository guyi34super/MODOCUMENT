package com.modocument.api.settings;

import com.modocument.application.mapping.MappingProperties;
import com.modocument.application.mapping.MappingSettings;
import com.modocument.application.port.TenantContext;
import com.modocument.application.privacy.MaskingPolicy;
import com.modocument.application.privacy.MaskingProperties;
import com.modocument.application.security.LoginLockoutPolicy;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.application.usecase.AuthUseCase;
import com.modocument.application.usecase.ChatEditProperties;
import com.modocument.application.usecase.ChatEditTableUseCase;
import com.modocument.doccreation.generate.AiDocumentComposer;
import com.modocument.doccreation.parse.AiSectionSplitter;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.alerting.AlertProperties;
import com.modocument.infrastructure.alerting.AlertRuleCatalogue;
import com.modocument.infrastructure.alerting.EmailAlertChannel;
import com.modocument.infrastructure.alerting.WebhookAlertChannel;
import com.modocument.infrastructure.export.ExportCapProperties;
import com.modocument.infrastructure.export.ExportGuard;
import com.modocument.infrastructure.file.ExcelParsingProperties;
import com.modocument.infrastructure.file.ParsingProperties;
import com.modocument.infrastructure.file.ParsingSettings;
import com.modocument.infrastructure.ratelimit.ApiRateLimitInterceptor;
import com.modocument.infrastructure.ratelimit.ApiRateLimitProperties;
import com.modocument.infrastructure.ratelimit.AuthRateLimiter;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import com.modocument.infrastructure.ratelimit.MfaRateLimiter;
import com.modocument.infrastructure.retention.RetentionProperties;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws20 Wave 2 PARITY GATE (plan §1, top risk #4): with NOTHING stored in
 * BankSetting, every refactored consumer — now reading through
 * SettingsReader per call — returns exactly what it returned before the
 * refactor: the value of the yml-bound property bean / {@code @Value} it
 * was built from, which for this (test-profile) context is also the shipped
 * application.yml value. One assertion per consumer value, enumerated, in
 * the real application context and for a real, freshly created bank.
 *
 * <p>The other half — a stored value changes behaviour, for that bank or
 * for all — is SettingsConsumersOverrideTest (unit) and
 * SettingsConsumersIntegrationTest (HTTP).
 */
class SettingsConsumersParityTest extends SettingsIntegrationSupport {

    @Autowired
    private Environment environment;
    @Autowired
    private PasswordPolicy passwordPolicy;
    @Autowired
    private LoginLockoutPolicy lockoutPolicy;
    @Autowired
    private AuthUseCase authUseCase;
    @Autowired
    private JwtService jwt;
    @Autowired
    private ApiRateLimitInterceptor apiRateLimits;
    @Autowired
    private ApiRateLimitProperties apiRateLimitProperties;
    @Autowired
    private LlmRateLimiter llmRateLimiter;
    @Autowired
    private AuthRateLimiter authRateLimiter;
    @Autowired
    private MfaRateLimiter mfaRateLimiter;
    @Autowired
    private ExportGuard exportGuard;
    @Autowired
    private ExportCapProperties exportCaps;
    @Autowired
    private RetentionProperties retention;
    @Autowired
    private AlertRuleCatalogue alertRules;
    @Autowired
    private AlertProperties alertProperties;
    @Autowired
    private WebhookAlertChannel webhookChannel;
    @Autowired
    private EmailAlertChannel emailChannel;
    @Autowired
    private MaskingPolicy maskingPolicy;
    @Autowired
    private MaskingProperties maskingProperties;
    @Autowired
    private ChatEditTableUseCase chatEdit;
    @Autowired
    private ChatEditProperties chatEditProperties;
    @Autowired
    private AiSectionSplitter sectionSplitter;
    @Autowired
    private AiDocumentComposer documentComposer;
    @Autowired
    private ParsingSettings parsingSettings;
    @Autowired
    private ParsingProperties parsingProperties;
    @Autowired
    private ExcelParsingProperties excelParsingProperties;
    @Autowired
    private MappingSettings mappingSettings;
    @Autowired
    private MappingProperties mappingProperties;

    private int bankId;

    @BeforeEach
    void nothingIsStored() {
        bankId = bank.getBankId();
        settingsCache.reload();
        assertThat(settingsCache.current().size())
                .as("parity runs with an EMPTY BankSetting table — a test before this one "
                        + "left settings rows behind")
                .isZero();
    }

    @Test
    void signInConsumersKeepTheirYmlValues() throws Exception {
        assertThat(passwordPolicy.minLength(bankId))
                .isEqualTo(property("modocument.security.password.min-length"))
                .isEqualTo(12);
        assertThat(TenantContext.runAs(bankId, () -> passwordPolicy.minLength())).isEqualTo(12);
        assertThat(passwordPolicy.minLength()).as("no tenant: platform layer").isEqualTo(12);

        assertThat(lockoutPolicy.allowedAttempts(bankId))
                .isEqualTo(property("modocument.security.lockout.allowed-attempts"))
                .isEqualTo(5);
        assertThat(lockoutPolicy.baseLockMinutes(bankId)).isEqualTo(1);
        assertThat(lockoutPolicy.capLockMinutes(bankId)).isEqualTo(30);

        assertThat(environment.getProperty("modocument.security.session.idle-timeout"))
                .isEqualTo("12h");
        assertThat(authUseCase.refreshIdleTimeout(bankId)).isEqualTo(Duration.ofHours(12));
        assertThat(authUseCase.mustEnrolMfa(userOf(Role.ADMIN))).isTrue();
        assertThat(authUseCase.mustEnrolMfa(userOf(Role.PLATFORM_ADMIN))).isTrue();
        for (Role role : new Role[] {Role.READ_ONLY, Role.READ_WRITE, Role.APPROVER}) {
            assertThat(authUseCase.mustEnrolMfa(userOf(role))).as(role.name()).isFalse();
        }
        assertThat(jwt.ttlMinutes())
                .isEqualTo(property("modocument.security.jwt.ttl-minutes"))
                .isEqualTo(15);
    }

    @Test
    void everyRateLimitBucketKeepsItsYmlLimit() {
        Map<String, Integer> expected = new TreeMap<>(Map.ofEntries(
                Map.entry("uploads", apiRateLimitProperties.getUploads()),
                Map.entry("ingestion", apiRateLimitProperties.getIngestion()),
                Map.entry("transform", apiRateLimitProperties.getTransform()),
                Map.entry("exports", apiRateLimitProperties.getExports()),
                Map.entry("doc-generate", apiRateLimitProperties.getDocGenerate()),
                Map.entry("extraction", apiRateLimitProperties.getExtraction()),
                Map.entry("dsr", apiRateLimitProperties.getDsr()),
                Map.entry("password-change", apiRateLimitProperties.getPasswordChange()),
                Map.entry("mfa-code", apiRateLimitProperties.getMfaCode()),
                Map.entry("admin-jobs", apiRateLimitProperties.getAdminJobs()),
                Map.entry("admin-security", apiRateLimitProperties.getAdminSecurity())));
        assertThat(apiRateLimits.settingKeys().keySet()).isEqualTo(expected.keySet());
        Map<String, Integer> actual = new TreeMap<>();
        for (String bucket : expected.keySet()) {
            actual.put(bucket, apiRateLimits.limitFor(bucket, bankId));
        }
        assertThat(actual).isEqualTo(expected).isEqualTo(Map.ofEntries(
                Map.entry("uploads", 20), Map.entry("ingestion", 60),
                Map.entry("transform", 30), Map.entry("exports", 60),
                Map.entry("doc-generate", 30), Map.entry("extraction", 30),
                Map.entry("dsr", 10), Map.entry("password-change", 5),
                Map.entry("mfa-code", 10), Map.entry("admin-jobs", 10),
                Map.entry("admin-security", 10)));

        assertThat(llmRateLimiter.limitFor(bankId)).isEqualTo(60);
        assertThat(authRateLimiter.perIpMax()).isEqualTo(30);
        assertThat(authRateLimiter.perEmailMax()).isEqualTo(10);
        // OBS-04: the per-address MFA flood guard ships at 30/min; per user stays 5.
        assertThat(mfaRateLimiter.perIpMax()).isEqualTo(30);
        assertThat(mfaRateLimiter.perUserMax()).isEqualTo(5);
    }

    @Test
    void dataProtectionConsumersKeepTheirYmlValues() throws Exception {
        for (Role role : Role.values()) {
            assertThat(exportGuard.capFor(role, bankId)).as(role.name())
                    .isEqualTo(exportCaps.capFor(role));
        }
        assertThat(exportGuard.capFor(Role.READ_WRITE, bankId)).isEqualTo(5_000);
        assertThat(exportGuard.capFor(Role.ADMIN, bankId)).isEqualTo(50_000);

        assertThat(retention.customerDataDays(bankId))
                .isEqualTo(property("modocument.retention.customer-data-days"))
                .isEqualTo(30);
        assertThat(retention.customerDataDaysForPlatform()).isEqualTo(30);
        assertThat(TenantContext.runAs(bankId, () -> retention.customerDataDays()))
                .isEqualTo(30);

        assertThat(maskingPolicy.screenWorkingMasked(bankId))
                .isEqualTo(maskingProperties.isScreenWorking()).isFalse();
        assertThat(TenantContext.runAs(bankId, () ->
                maskingPolicy.mask(MaskingPolicy.Channel.SCREEN_WORKING, Role.READ_WRITE)))
                .isFalse();
    }

    @Test
    void theAlertCatalogueIsExactlyTheYmlCatalogue() {
        assertThat(alertRules.all())
                .isEqualTo(new AlertRuleCatalogue(alertProperties).all());
        assertThat(webhookChannel.minSeverity())
                .isEqualTo(alertProperties.getWebhook().getMinSeverity())
                .isEqualTo(AlertSeverity.INFO);
        assertThat(emailChannel.minSeverity())
                .isEqualTo(alertProperties.getEmail().getMinSeverity())
                .isEqualTo(AlertSeverity.INFO);
    }

    @Test
    void aiAndExtractionConsumersKeepTheirYmlValues() throws Exception {
        TenantContext.runAs(bankId, () -> {
            assertThat(chatEdit.maxChangeRatio())
                    .isEqualTo(chatEditProperties.getFullTableMaxChangeRatio())
                    .isEqualTo(0.6);
            assertThat(sectionSplitter.enabled()).isTrue();
            assertThat(documentComposer.enabled()).isTrue();
        });

        ParsingProperties parsing = parsingSettings.forBank(bankId);
        assertThat(parsing).usingRecursiveComparison().isEqualTo(parsingProperties);
        assertThat(parsing.getLlmBatchSize()).isEqualTo(50);
        assertThat(parsing.getLlmBatchConcurrency()).isEqualTo(8);
        assertThat(parsing.isDeterministicEnabled()).isTrue();
        assertThat(parsing.isCombinedExtractionMapping()).isTrue();
        assertThat(parsingSettings.forCurrentBank()).usingRecursiveComparison()
                .isEqualTo(parsingProperties);
        assertThat(parsingSettings.excelForBank(bankId).isLlmEnabled())
                .isEqualTo(excelParsingProperties.isLlmEnabled()).isFalse();

        MappingProperties mapping = mappingSettings.forBank(bankId);
        assertThat(mapping).usingRecursiveComparison().isEqualTo(mappingProperties);
        assertThat(mapping.isLlmFallbackEnabled()).isTrue();
        assertThat(mapping.getConfidenceThreshold()).isEqualTo(0.7);
    }

    private int property(String name) {
        Integer value = environment.getProperty(name, Integer.class);
        assertThat(value).as(name).isNotNull();
        return value;
    }

    private AppUser userOf(Role role) {
        AppUser user = new AppUser();
        user.setAppUserId(1);
        user.setBankId(bankId);
        user.setRole(role);
        return user;
    }
}
