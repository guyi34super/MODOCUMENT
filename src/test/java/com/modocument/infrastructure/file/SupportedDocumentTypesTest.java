package com.modocument.infrastructure.file;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupportedDocumentTypesTest {

    private final DoclingClient doclingClient = mock(DoclingClient.class);
    private final SupportedDocumentTypes types = new SupportedDocumentTypes(
            new TabularNormalizer(),
            new CompositeDocumentParser(doclingClient, mock(FileTextReader.class)));

    @Test
    void acceptsBuiltInTypesRegardlessOfDocling() {
        when(doclingClient.isEnabled()).thenReturn(false);

        assertThat(types.isSupported("people.csv")).isTrue();
        assertThat(types.isSupported("REPORT.XLSX")).isTrue();
        assertThat(types.isSupported("scan.pdf")).isTrue();
        assertThat(types.isSupported("photo.jpeg")).isTrue();
    }

    @Test
    void rejectsUnknownTypesAndMissingExtensions() {
        when(doclingClient.isEnabled()).thenReturn(false);

        assertThat(types.isSupported("malware.exe")).isFalse();
        assertThat(types.isSupported("archive.zip")).isFalse();
        assertThat(types.isSupported("noextension")).isFalse();
        assertThat(types.isSupported(null)).isFalse();
    }

    @Test
    void doclingOnlyTypesRequireDocling() {
        when(doclingClient.isEnabled()).thenReturn(false);
        assertThat(types.isSupported("slides.pptx")).isFalse();

        when(doclingClient.isEnabled()).thenReturn(true);
        when(doclingClient.supports(".pptx")).thenReturn(true);
        when(doclingClient.supportedExtensions()).thenReturn(Set.of(".pptx"));
        assertThat(types.isSupported("slides.pptx")).isTrue();
        assertThat(types.describeSupported()).contains("pptx");
    }

    @Test
    void describesSupportedTypesSortedWithoutDots() {
        when(doclingClient.isEnabled()).thenReturn(false);

        String described = types.describeSupported();
        assertThat(described).contains("csv").contains("pdf").contains("xlsx");
        assertThat(described).doesNotContain(".");
    }
}
