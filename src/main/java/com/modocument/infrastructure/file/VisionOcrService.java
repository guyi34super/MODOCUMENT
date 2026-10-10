package com.modocument.infrastructure.file;

import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.infrastructure.ai.AiGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * OCR via the configured vision model, routed through the {@link AiGateway}
 * like every other LLM call (tenancy check, no content logging, AI_CALL
 * evidence). A page image cannot be tokenized, so this tier is OFF BY
 * DEFAULT (gate C8 local-first posture) and requires the explicit
 * {@code MODOCUMENT_AI_VISION_OCR_ENABLED=true} opt-in plus a real API key;
 * the Tesseract {@link OcrService} remains the offline default, and any
 * failure here degrades to returning no text instead of failing the
 * transform.
 */
@Component
public class VisionOcrService {

    static final String OCR_PROMPT = """
            Transcribe ALL text visible in this image exactly as written.
            Preserve reading order and line breaks; render tables as GitHub markdown tables.
            Output only the transcribed text - no commentary, no code fences.
            If the image contains no text, output nothing.""";

    private static final Logger log = LoggerFactory.getLogger(VisionOcrService.class);

    private final AiGateway aiGateway;
    private final VisionOcrProperties properties;

    public VisionOcrService(AiGateway aiGateway, VisionOcrProperties properties) {
        this.aiGateway = aiGateway;
        this.properties = properties;
    }

    /** True when vision OCR is explicitly opted in AND a real API key is configured. */
    public boolean isEnabled() {
        return properties.isEnabled() && aiGateway.isConfigured();
    }

    public int maxPagesPerDocument() {
        return properties.getMaxPages();
    }

    public int renderDpi() {
        return properties.getDpi();
    }

    /** OCRs one image via the vision model; empty when disabled or on any failure. */
    public String extractText(BufferedImage image) {
        if (!isEnabled() || image == null) {
            return "";
        }
        try {
            // OpenAI vision accepts png/jpeg/gif/webp; inputs are already
            // decoded BufferedImages, so always re-encode to PNG.
            byte[] png = toPng(downscale(image, properties.getMaxDimension()));
            String text = aiGateway.ocrImage(
                    PromptEnvelope.of(AiPurpose.VISION_OCR, OCR_PROMPT, properties.getModel()),
                    png);
            return text == null ? "" : text.strip();
        } catch (Exception e) {
            log.warn("Vision OCR failed, falling back to local OCR: {}", e.getMessage());
            return "";
        }
    }

    private static BufferedImage downscale(BufferedImage image, int maxDimension) {
        int longestEdge = Math.max(image.getWidth(), image.getHeight());
        if (maxDimension <= 0 || longestEdge <= maxDimension) {
            return image;
        }
        double scale = (double) maxDimension / longestEdge;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static byte[] toPng(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not encode image as PNG", e);
        }
        return out.toByteArray();
    }
}
