package com.modocument.application.usecase;

import com.modocument.application.dto.SaveExtractionSourceRequest;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.document.ApiSourceConfig;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionSourceStore;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Secret-exfiltration guard at the source of truth: a REST API source may
 * only reference env vars in the dedicated MODOCUMENT_CONNECTOR_ namespace.
 * Without this, saving a source with authEnvVar=MODOCUMENT_JWT_SECRET (or
 * any cloud credential) and pointing baseUrl at an attacker host would mail
 * the server's secrets out as an Authorization header.
 */
class ExtractionSourceAuthEnvVarTest {

    private final InMemoryExtractionSourceStore sources = new InMemoryExtractionSourceStore();
    private final ExtractionSourceUseCase useCase = new ExtractionSourceUseCase(
            sources, mock(TemplateRepository.class), FixedUser.provider(1));

    private static SaveExtractionSourceRequest apiRequest(String authEnvVar) {
        SaveExtractionSourceRequest request = new SaveExtractionSourceRequest();
        request.setName("API source");
        request.setType(SourceType.REST_API);
        request.setTermsAcknowledged(true);
        ApiSourceConfig api = new ApiSourceConfig();
        api.setBaseUrl("https://api.example.com");
        api.setAuthType(ApiSourceConfig.AuthType.BEARER);
        api.setAuthEnvVar(authEnvVar);
        request.setApi(api);
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MODOCUMENT_JWT_SECRET",
            "MODOCUMENT_DB_PASSWORD",
            "AWS_SECRET_ACCESS_KEY",
            "PATH",
            "MODOCUMENT_CONNECTOR_", // prefix alone names nothing
            "modocument_connector_github" // env vars are case-sensitive; no sneaking past
    })
    void createRejectsEnvVarsOutsideTheConnectorNamespace(String envVar) {
        assertThatThrownBy(() -> useCase.create(apiRequest(envVar)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MODOCUMENT_CONNECTOR_");
    }

    @Test
    void updateRejectsSmugglingABadEnvVarIntoAnExistingSource() {
        ExtractionSource created = useCase.create(apiRequest("MODOCUMENT_CONNECTOR_GITHUB"));

        assertThatThrownBy(() -> useCase.update(created.getId(), apiRequest("MODOCUMENT_JWT_SECRET")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MODOCUMENT_CONNECTOR_");

        // The stored source is untouched.
        assertThat(useCase.get(created.getId()).getApi().getAuthEnvVar())
                .isEqualTo("MODOCUMENT_CONNECTOR_GITHUB");
    }

    @Test
    void allowlistedEnvVarAndNoEnvVarAreAccepted() {
        assertThat(useCase.create(apiRequest("MODOCUMENT_CONNECTOR_GITHUB_TOKEN"))).isNotNull();
        assertThat(useCase.create(apiRequest(null))).isNotNull();
        assertThat(useCase.create(apiRequest(""))).isNotNull();
    }
}
