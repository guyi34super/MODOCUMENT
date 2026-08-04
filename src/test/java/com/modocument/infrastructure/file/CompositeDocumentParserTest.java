package com.modocument.infrastructure.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompositeDocumentParserTest {

    private final DoclingClient docling = mock(DoclingClient.class);
    private final FileTextReader fileTextReader = mock(FileTextReader.class);
    private CompositeDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new CompositeDocumentParser(docling, fileTextReader);
    }

    @Test
    void usesLocalReadersForSupportedFormats() {
        when(docling.isEnabled()).thenReturn(false);
        when(fileTextReader.readAsText(anyString())).thenReturn("local text");

        assertThat(parser.extractText(Path.of("book.xlsx"))).isEqualTo("local text");
        assertThat(parser.extractText(Path.of("doc.pdf"))).isEqualTo("local text");
        verify(docling, never()).convertToMarkdown(any());
    }

    @Test
    void neverUsesDoclingWhenDisabled() {
        when(docling.isEnabled()).thenReturn(false);
        when(fileTextReader.readAsText(anyString())).thenReturn("local docx text");

        var result = parser.extract(Path.of("doc.docx"));

        assertThat(result.text()).isEqualTo("local docx text");
        assertThat(result.extractionSource()).isEqualTo("local");
        verify(docling, never()).convertToMarkdown(any());
    }

    @Test
    void docxUsesLocalReaderEvenWhenDoclingEnabled() {
        when(docling.isEnabled()).thenReturn(true);
        when(docling.supports(".docx")).thenReturn(true);
        when(fileTextReader.readAsText(anyString())).thenReturn("local docx text");

        var result = parser.extract(Path.of("doc.docx"));

        assertThat(result.text()).isEqualTo("local docx text");
        assertThat(result.extractionSource()).isEqualTo("local");
        verify(docling, never()).convertToMarkdown(any());
    }

    @Test
    void unsupportedTypeFailsWhenDoclingDisabled() {
        when(docling.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> parser.extract(Path.of("slides.pptx")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void supportsLocalFormatsAlways() {
        when(docling.isEnabled()).thenReturn(false);

        assertThat(parser.supports(".docx")).isTrue();
        assertThat(parser.supports(".png")).isTrue();
        assertThat(parser.supports(".txt")).isTrue();
        assertThat(parser.supports(".xlsm")).isTrue();
        assertThat(parser.supports(".zip")).isFalse();
    }
}
