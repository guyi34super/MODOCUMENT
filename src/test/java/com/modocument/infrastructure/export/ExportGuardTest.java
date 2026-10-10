package com.modocument.infrastructure.export;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

/**
 * The cap semantics of ws18's export guard (gate D2, PRIV-002): per-role
 * caps, refusal without a reason (audited DENIED), allowance with one
 * (audited SUCCESS, reason verbatim), fail-closed for unconfigured roles and
 * strict reason validation.
 */
class ExportGuardTest {

    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final ExportCapProperties properties = new ExportCapProperties();

    private ExportGuard guard(Role role) {
        return new ExportGuard(properties, audit, new FixedUser(7, 3, role));
    }

    private static ExportGuard.Attempt attempt(int rows, String reason) {
        return new ExportGuard.Attempt(11, "file", "CSV", rows, reason);
    }

    private AuditEventSpec recorded() {
        ArgumentCaptor<AuditEventSpec> spec = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(spec.capture());
        return spec.getValue();
    }

    @Test
    void defaultsAreFiveThousandForReadWriteAndFiftyThousandForAdmin() {
        assertThat(properties.capFor(Role.READ_WRITE)).isEqualTo(5_000);
        assertThat(properties.capFor(Role.ADMIN)).isEqualTo(50_000);
        // Fail closed: a role without a configured cap needs a reason for any row.
        assertThat(properties.capFor(Role.READ_ONLY)).isZero();
        assertThat(properties.capFor(null)).isZero();
    }

    @Test
    void theShippedConfigurationBindsThePerRoleCapsAndIsEnvOverridable() throws Exception {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(yaml.get(0));
        ExportCapProperties shipped = bind(sources);
        assertThat(shipped.capFor(Role.READ_WRITE)).isEqualTo(5_000);
        assertThat(shipped.capFor(Role.ADMIN)).isEqualTo(50_000);
        assertThat(shipped.getReasonMaxLength()).isEqualTo(500);

        // MODOCUMENT_EXPORT_CAP_READ_WRITE=100 (the placeholder's env variable).
        sources.addFirst(new MapPropertySource("env",
                Map.<String, Object>of("MODOCUMENT_EXPORT_CAP_READ_WRITE", "100")));
        assertThat(bind(sources).capFor(Role.READ_WRITE)).isEqualTo(100);
    }

    private static ExportCapProperties bind(MutablePropertySources sources) {
        return new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources))
                .bind("modocument.export", Bindable.of(ExportCapProperties.class))
                .orElseThrow(() -> new AssertionError("modocument.export missing"));
    }

    @Test
    void atOrUnderTheCapIsAllowedSilently() {
        ExportGuard.Decision decision = guard(Role.READ_WRITE).authorize(attempt(5_000, null));

        assertThat(decision.overCap()).isFalse();
        assertThat(decision.cap()).isEqualTo(5_000);
        verify(audit, never()).record(any());
    }

    @Test
    void overTheCapWithoutAReasonIsRefusedAndAuditedDenied() {
        assertThatThrownBy(() -> guard(Role.READ_WRITE).authorize(attempt(10_000, "   ")))
                .isInstanceOf(ExportCapExceededException.class)
                .satisfies(e -> {
                    ExportCapExceededException cap = (ExportCapExceededException) e;
                    assertThat(cap.cap()).isEqualTo(5_000);
                    assertThat(cap.rows()).isEqualTo(10_000);
                });

        AuditEventSpec spec = recorded();
        assertThat(spec.action()).isEqualTo("EXPORT_BLOCKED");
        assertThat(spec.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(spec.entityId()).isEqualTo("11");
        assertThat(values(spec)).containsEntry("rows", 10_000)
                .containsEntry("cap", 5_000).containsEntry("role", "READ_WRITE");
    }

    @Test
    void overTheCapWithAReasonIsAllowedAndTheReasonRecordedVerbatim() {
        ExportGuard.Decision decision = guard(Role.READ_WRITE)
                .authorize(attempt(10_000, "  Regulator return\u0007 Q3  "));

        assertThat(decision.overCap()).isTrue();
        assertThat(decision.reason()).isEqualTo("Regulator return Q3");
        AuditEventSpec spec = recorded();
        assertThat(spec.action()).isEqualTo("EXPORT_OVER_CAP_WITH_REASON");
        assertThat(spec.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(values(spec)).containsEntry("reason", "Regulator return Q3");
    }

    @Test
    void anAdminHasItsOwnCap() {
        assertThat(guard(Role.ADMIN).authorize(attempt(10_000, null)).overCap()).isFalse();
        assertThatThrownBy(() -> guard(Role.ADMIN).authorize(attempt(50_001, null)))
                .isInstanceOf(ExportCapExceededException.class);
    }

    @Test
    void anOverLongReasonIsABadRequestNotATruncation() {
        assertThatThrownBy(() -> guard(Role.READ_WRITE).authorize(attempt(10, "x".repeat(501))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
        verify(audit, never()).record(any());
    }

    @Test
    void theExportLogRowCarriesFormatRowCountAndCapDecision() {
        ExportGuard guard = guard(Role.READ_WRITE);
        guard.recordExport(attempt(4, null), new ExportGuard.Decision(5_000, false, null), 4);

        AuditEventSpec spec = recorded();
        assertThat(spec.action()).isEqualTo("EXPORT_RUN");
        assertThat(spec.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(values(spec)).containsEntry("format", "CSV")
                .containsEntry("rowCount", 4).containsEntry("overCap", false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(AuditEventSpec spec) {
        return (Map<String, Object>) spec.newValues();
    }
}
