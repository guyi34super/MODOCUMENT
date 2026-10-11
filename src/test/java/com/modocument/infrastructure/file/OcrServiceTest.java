package com.modocument.infrastructure.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws29: the runtime image installs Debian's tesseract-ocr + tesseract-ocr-eng
 * and sets TESSDATA_PREFIX to their tessdata. The availability tests run
 * wherever those packages are installed (the image, or a dev box after
 * {@code apt-get install tesseract-ocr tesseract-ocr-eng}) and are skipped
 * elsewhere.
 */
class OcrServiceTest {

    /** Where Ubuntu 24.04 (the eclipse-temurin:21-jre base) installs the eng traineddata. */
    static final Path DEBIAN_TESSDATA = Path.of("/usr/share/tesseract-ocr/5/tessdata");

    static boolean tesseractInstalled() {
        return Files.isRegularFile(DEBIAN_TESSDATA.resolve("eng.traineddata"));
    }

    @Test
    void tessdataComesFromTessdataPrefixWithTheOldRelativeDefault() {
        assertThat(new OcrService("/opt/tessdata").datapath()).isEqualTo("/opt/tessdata");
        assertThat(new OcrService(" ").datapath()).isEqualTo("tessdata");
        assertThat(new OcrService(null).datapath()).isEqualTo("tessdata");
    }

    @Test
    void missingTraineddataDegradesToNoText() {
        OcrService ocr = new OcrService("/nonexistent/tessdata");
        assertThat(ocr.isAvailable()).isFalse();
        assertThat(ocr.extractText(new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY))).isEmpty();
    }

    @Test
    @EnabledIf("tesseractInstalled")
    void theDebianPackagesMakeOcrAvailable() {
        OcrService ocr = new OcrService(DEBIAN_TESSDATA.toString());
        assertThat(ocr.isAvailable()).isTrue();

        BufferedImage image = new BufferedImage(600, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 120);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 48));
        g.drawString("INVOICE 2026", 20, 80);
        g.dispose();
        assertThat(ocr.extractText(image)).contains("INVOICE");
    }
}
