package com.modocument.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlNormalizerTest {

    @Test
    void lowercasesSchemeAndHostAndDropsFragment() {
        assertThat(UrlNormalizer.normalize("HTTPS://Example.COM/Path#section"))
                .isEqualTo("https://example.com/Path");
    }

    @Test
    void dropsDefaultPortsAndKeepsCustomOnes() {
        assertThat(UrlNormalizer.normalize("https://example.com:443/a"))
                .isEqualTo("https://example.com/a");
        assertThat(UrlNormalizer.normalize("http://example.com:8080/a"))
                .isEqualTo("http://example.com:8080/a");
    }

    @Test
    void sortsQueryAndDropsTrackingParams() {
        assertThat(UrlNormalizer.normalize("https://example.com/p?b=2&utm_source=x&a=1"))
                .isEqualTo("https://example.com/p?a=1&b=2");
    }

    @Test
    void emptyPathBecomesRoot() {
        assertThat(UrlNormalizer.normalize("https://example.com"))
                .isEqualTo("https://example.com/");
    }

    @Test
    void nonHttpSchemesAreRejected() {
        assertThat(UrlNormalizer.normalize("ftp://example.com/file")).isNull();
        assertThat(UrlNormalizer.normalize("javascript:alert(1)")).isNull();
        assertThat(UrlNormalizer.normalize("mailto:a@b.c")).isNull();
    }

    @Test
    void resolvesRelativeLinks() {
        assertThat(UrlNormalizer.resolve("https://example.com/docs/index.html", "page2.html"))
                .isEqualTo("https://example.com/docs/page2.html");
        assertThat(UrlNormalizer.resolve("https://example.com/docs/", "/root.html"))
                .isEqualTo("https://example.com/root.html");
    }

    @Test
    void hostOfExtractsLowercasedHost() {
        assertThat(UrlNormalizer.hostOf("https://Sub.Example.COM/x")).isEqualTo("sub.example.com");
        assertThat(UrlNormalizer.hostOf("not a url")).isEmpty();
    }
}
