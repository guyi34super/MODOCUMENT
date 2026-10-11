package com.modocument.infrastructure.file;

import net.sourceforge.tess4j.Tesseract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OCR fallback for image-only PDF pages. Tesseract needs its native library
 * (libtesseract, loaded by tess4j through JNA) and the eng traineddata.
 * The traineddata directory is {@code TESSDATA_PREFIX} when set — the
 * runtime image installs the Debian {@code tesseract-ocr} +
 * {@code tesseract-ocr-eng} packages and points it at their tessdata (ws29)
 * — otherwise {@code ./tessdata} like the original backend. When either
 * piece is missing the service degrades to returning no text instead of
 * failing the whole transform, and says so once in the log.
 */
@Component
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final String TRAINEDDATA = "eng.traineddata";

    private final String datapath;
    private volatile boolean unavailable;
    private volatile Boolean probed;

    /** {@code TESSDATA_PREFIX} reaches Spring as a property from the environment (the image sets it). */
    public OcrService(@Value("${TESSDATA_PREFIX:}") String tessdataPrefix) {
        this.datapath = tessdataPrefix == null || tessdataPrefix.isBlank() ? "tessdata" : tessdataPrefix.strip();
    }

    String datapath() {
        return datapath;
    }

    public String extractText(BufferedImage image) {
        if (unavailable || image == null) {
            return "";
        }
        if (!traineddataPresent()) {
            unavailable = true;
            log.warn("OCR unavailable, continuing without it: no {} in {}", TRAINEDDATA, datapath);
            return "";
        }
        try {
            return tesseract().doOCR(image);
        } catch (Throwable t) {
            // UnsatisfiedLinkError, missing traineddata, etc. — OCR is optional.
            unavailable = true;
            log.warn("OCR unavailable, continuing without it: {}", t.getMessage());
            return "";
        }
    }

    /**
     * Whether Tesseract actually works here: one OCR call on a blank image
     * (loads the native library and the traineddata), cached. For startup
     * diagnostics and the image smoke check (docs/release/runbook.md).
     */
    public boolean isAvailable() {
        Boolean result = probed;
        if (result == null) {
            synchronized (this) {
                if (probed == null) {
                    probed = probe();
                }
                result = probed;
            }
        }
        return result;
    }

    private boolean probe() {
        if (unavailable) {
            return false;
        }
        // tess4j does NOT fail on a missing language file (Tesseract prints
        // "couldn't load any languages" and returns no text), so check it.
        if (!traineddataPresent()) {
            log.warn("Tesseract OCR unavailable: no {} in {}", TRAINEDDATA, datapath);
            return false;
        }
        try {
            BufferedImage blank = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY);
            tesseract().doOCR(blank);
            log.info("Tesseract OCR available (tessdata {})", datapath);
            return true;
        } catch (Throwable t) {
            log.warn("Tesseract OCR unavailable (tessdata {}): {}", datapath, t.getMessage());
            return false;
        }
    }

    private boolean traineddataPresent() {
        return Files.isRegularFile(Path.of(datapath, TRAINEDDATA));
    }

    private Tesseract tesseract() {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(datapath);
        tesseract.setLanguage("eng");
        return tesseract;
    }
}
