package com.modocument.infrastructure.file;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompositeDocumentParserTest {

    private final DoclingClient docling = mock(DoclingClient.class);
    private final FileTextReader fileTextReader = mock(FileTextReader.class);
    private final CompositeDocumentParser parser =
            new CompositeDocumentParser(docling, fileTextReader);

    @Test
    void usesLocalReadersWhenDoclingIsDisabled() {
        when(docling.isEnabled()).thenReturn(false);
        when(fileTextReader.readAsText(anyString())).thenReturn("local text");

        assertThat(parser.extractText(Path.of("doc.pdf"))).isEqualTo("local text");
        verify(docling, never()).convertToMarkdown(any());
    }

    @Test
    void usesDoclingWhenEnabledAndSupported() {
        when(docling.isEnabled()).thenReturn(true);
        when(docling.supports(".pdf")).thenReturn(true);
        when(docling.convertToMarkdown(any())).thenReturn("# markdown");

        assertThat(parser.extractText(Path.of("doc.pdf"))).isEqualTo("# markdown");
        verify(fileTextReader, never()).readAsText(anyString());
    }

    @Test
    void fallsBackToLocalReadersWhenDoclingFails() {
        when(docling.isEnabled()).thenReturn(true);
        when(docling.supports(".pdf")).thenReturn(true);
        when(docling.convertToMarkdown(any())).thenThrow(new IllegalStateException("down"));
        when(fileTextReader.readAsText(anyString())).thenReturn("fallback text");

        assertThat(parser.extractText(Path.of("doc.pdf"))).isEqualTo("fallback text");
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
