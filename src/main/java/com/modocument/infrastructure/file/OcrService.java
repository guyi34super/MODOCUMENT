package com.modocument.infrastructure.file;

import net.sourceforge.tess4j.Tesseract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

/**
 * OCR fallback for image-only PDF pages. Tesseract needs a native library and
 * an eng traineddata file (looked up in ./tessdata like the original backend);
 * when either is missing the service degrades to returning no text instead of
 * failing the whole transform.
 */
@Component
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private volatile boolean unavailable;

    public String extractText(BufferedImage image) {
        if (unavailable || image == null) {
            return "";
        }
        try {
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath("tessdata");
            tesseract.setLanguage("eng");
            return tesseract.doOCR(image);
        } catch (Throwable t) {
            // UnsatisfiedLinkError, missing traineddata, etc. — OCR is optional.
            unavailable = true;
            log.warn("OCR unavailable, continuing without it: {}", t.getMessage());
            return "";
        }
    }
}
