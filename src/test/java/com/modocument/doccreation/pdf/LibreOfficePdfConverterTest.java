package com.modocument.doccreation.pdf;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibreOfficePdfConverterTest {

    @Test
    void aMissingBinaryMeansUnavailableAndAClearConvertError() {
        LibreOfficePdfConverter converter =
                new LibreOfficePdfConverter("/definitely/not/a/real/soffice", 5);

        assertThat(converter.isAvailable()).isFalse();
        assertThatThrownBy(() -> converter.convert(Path.of("whatever.docx")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("download the .docx instead");
    }
}
