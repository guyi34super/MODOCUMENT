package com.modocument.infrastructure.security.client;

import com.modocument.api.integration.TestCertificates;
import com.modocument.domain.entity.ApiClient;
import com.modocument.domain.model.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ws26 building blocks: credential formats, CIDR syntax, scopes, mTLS header trust. */
class ClientCredentialPrimitivesTest {

    @Test
    void credentialsHaveTheirDocumentedShapes() {
        assertThat(ClientCredentials.newClientId()).matches("mdc_[a-z0-9]{24}");
        assertThat(ClientCredentials.newKeyPrefix()).matches("mdk_[a-z0-9]{16}");
        assertThat(ClientCredentials.newSecret()).matches("[A-Za-z0-9_-]{43}");
        assertThat(ClientCredentials.newSecret()).isNotEqualTo(ClientCredentials.newSecret());
        assertThat(ClientCredentials.apiKey("mdk_x", "s")).isEqualTo("mdk_x.s");
    }

    @Test
    void cidrsAreIpLiteralsOnlyNeverHostNames() {
        assertThat(Cidrs.normalize(" 203.0.113.0/24 ")).isEqualTo("203.0.113.0/24");
        assertThat(Cidrs.normalize("198.51.100.7")).isEqualTo("198.51.100.7/32");
        assertThat(Cidrs.normalize("2001:DB8::/32")).isEqualTo("2001:db8::/32");
        for (String bad : List.of("bank.example", "10.0.0.0/33", "256.1.1.1", "10.0.0.0/x",
                "", "1.2.3", "::1::2/64")) {
            assertThatThrownBy(() -> Cidrs.normalize(bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void allowedCidrsMatchTheCallerOrAnyoneWhenEmpty() {
        ApiClient client = new ApiClient();
        assertThat(ApiClientAuthenticator.ipAllowed(client, "198.51.100.7")).isTrue();
        client.setAllowedCidrList(List.of("203.0.113.0/24", "2001:db8::/32"));
        assertThat(ApiClientAuthenticator.ipAllowed(client, "203.0.113.99")).isTrue();
        assertThat(ApiClientAuthenticator.ipAllowed(client, "2001:db8::5")).isTrue();
        assertThat(ApiClientAuthenticator.ipAllowed(client, "198.51.100.7")).isFalse();
        assertThat(ApiClientAuthenticator.ipAllowed(client, "")).isFalse();
    }

    @Test
    void scopesParseFailClosed() {
        assertThat(ClientTokenService.parseScopes("INTEGRATION_INGEST integration_events NOPE"))
                .containsExactlyInAnyOrder(Permission.INTEGRATION_INGEST,
                        Permission.INTEGRATION_EVENTS);
        ApiClient client = new ApiClient();
        client.setScopes("INTEGRATION_DOCS,RENAMED_LATER");
        assertThat(client.scopeSet()).containsExactly(Permission.INTEGRATION_DOCS);
        assertThat(ClientTokenService.formatScopes(Set.of(Permission.INTEGRATION_RESULTS,
                Permission.INTEGRATION_DOCS))).isEqualTo("INTEGRATION_DOCS INTEGRATION_RESULTS");
    }

    @Test
    void certificateHeadersCountOnlyFromATrustedSocketPeer() throws Exception {
        IntegrationClientProperties properties = new IntegrationClientProperties();
        properties.getClients().setMtlsTrustedProxyCidrs(List.of("10.0.0.0/8"));
        MtlsClientCertificates certificates = new MtlsClientCertificates(properties);
        String pem = TestCertificates.selfSignedPem("unit");

        MockHttpServletRequest fromProxy = new MockHttpServletRequest();
        fromProxy.setRemoteAddr("10.1.2.3");
        fromProxy.addHeader(MtlsClientCertificates.HEADER_LEAF, TestCertificates.headerValue(pem));
        MtlsClientCertificates.PresentedCertificate presented =
                certificates.presented(fromProxy).orElseThrow();
        assertThat(presented.fingerprintSha256()).matches("[0-9a-f]{64}")
                .isEqualTo(MtlsClientCertificates.fingerprint(MtlsClientCertificates.parsePem(pem)));
        assertThat(presented.subject()).contains("CN=unit");

        // A forwarded-header wrapper claiming a trusted address does not
        // change the socket peer underneath.
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("203.0.113.5");
        direct.addHeader(MtlsClientCertificates.HEADER_LEAF, TestCertificates.headerValue(pem));
        HttpServletRequestWrapper spoofed = new HttpServletRequestWrapper(direct) {
            @Override
            public String getRemoteAddr() {
                return "10.9.9.9";
            }
        };
        assertThat(certificates.presented(spoofed)).isEmpty();

        // No trusted proxies configured (the default): never trusted.
        MtlsClientCertificates none = new MtlsClientCertificates(new IntegrationClientProperties());
        assertThat(none.presented(fromProxy)).isEmpty();

        // Garbage from the proxy is no certificate.
        MockHttpServletRequest garbage = new MockHttpServletRequest();
        garbage.setRemoteAddr("10.1.2.3");
        garbage.addHeader(MtlsClientCertificates.HEADER_LEAF, "not-a-certificate");
        assertThat(certificates.presented(garbage)).isEmpty();
    }

    @Test
    void theTokenThumbprintIsRfc8705Base64Url() {
        String hex = "c698544a37b452ba1a1e86e47a45858b992f51ce83e4328746c4035891d8d9cc";
        assertThat(ClientTokenService.x5tS256(hex)).matches("[A-Za-z0-9_-]{43}");
    }
}
