package com.modocument.infrastructure.file;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Magic-byte checks (SEC-030): for every allow-listed extension the content
 * has to look like the claimed format — a renamed executable must not pass as
 * .csv or .xlsx.
 */
class UploadContentCheckerTest {

    private final UploadContentChecker checker = new UploadContentChecker();

    private static final byte[] ZIP = {'P', 'K', 3, 4, 20, 0, 0, 0};
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] EXE = exeHead();

    /** An MZ executable head: binary, with NUL bytes, no office signature. */
    private static byte[] exeHead() {
        byte[] head = new byte[512];
        head[0] = 'M';
        head[1] = 'Z';
        return head;
    }

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------- families that pass ----

    @Test
    void legitimateFilesOfEachFamilyPass() {
        assertThatCode(() -> {
            checker.requireContentMatchesExtension("data.csv", text("a,b\n1,2\n"));
            checker.requireContentMatchesExtension("notes.txt", text("plain text"));
            checker.requireContentMatchesExtension("page.html", text("<html></html>"));
            checker.requireContentMatchesExtension("book.xlsx", ZIP);
            checker.requireContentMatchesExtension("book.xlsm", ZIP);
            checker.requireContentMatchesExtension("report.docx", ZIP);
            checker.requireContentMatchesExtension("deck.pptx", ZIP);
            checker.requireContentMatchesExtension("legacy.xls", OLE2);
            checker.requireContentMatchesExtension("legacy.doc", OLE2);
            checker.requireContentMatchesExtension("file.pdf", text("%PDF-1.7\n..."));
            checker.requireContentMatchesExtension("image.png", PNG);
            checker.requireContentMatchesExtension("photo.jpg", JPEG);
            checker.requireContentMatchesExtension("photo.jpeg", JPEG);
            checker.requireContentMatchesExtension("anim.gif", text("GIF89a"));
            checker.requireContentMatchesExtension("bitmap.bmp", text("BM0000"));
            checker.requireContentMatchesExtension("scan.tif",
                    new byte[] {'I', 'I', 0x2A, 0x00, 1});
            checker.requireContentMatchesExtension("scan.tiff",
                    new byte[] {'M', 'M', 0x00, 0x2A, 1});
            checker.requireContentMatchesExtension("pic.webp",
                    text("RIFF\0\0\0\0WEBPVP8 "));
        }).doesNotThrowAnyException();
    }

    @Test
    void pdfHeaderMayFollowAShortPreamble() {
        byte[] preambled = text("﻿junk-preamble %PDF-1.4");
        assertThatCode(() -> checker.requireContentMatchesExtension("file.pdf", preambled))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownExtensionsPassThrough() {
        // The SupportedDocumentTypes allowlist bounds what arrives here; a
        // format without signature knowledge (docling extras) is not blocked.
        assertThatCode(() -> checker.requireContentMatchesExtension("x.unknownext", EXE))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------- disguises that fail ----

    @Test
    void renamedExecutableDoesNotPassAsCsvOrXlsx() {
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("report.csv", EXE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".csv");
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("report.xlsx", EXE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".xlsx");
    }

    @Test
    void pngRenamedToOfficeOrPdfIsRejected() {
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("sheet.xlsx", PNG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("sheet.xls", PNG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("doc.docx", PNG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("doc.pdf", PNG))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zipRenamedToImageIsRejected() {
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("image.png", ZIP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("image.jpg", ZIP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void binaryWithNulBytesIsNotText() {
        byte[] sneaky = Arrays.copyOf(text("a,b,c\n"), 64); // trailing NULs
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("data.csv", sneaky))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("data.txt", sneaky))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyHeadCountsAsTextButNotAsASignedFormat() {
        assertThatCode(() -> checker.requireContentMatchesExtension("empty.csv", new byte[0]))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> checker.requireContentMatchesExtension("empty.xlsx", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
