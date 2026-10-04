package com.modocument.infrastructure.file;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for LLM vision OCR of standalone images and scanned PDF pages.
 * OFF BY DEFAULT (gate C8 local-first posture): page images cannot be
 * tokenized, so sending them to a vision model is an explicit opt-in
 * ({@code MODOCUMENT_AI_VISION_OCR_ENABLED=true}) on top of a real API key.
 * The Tesseract-based {@link OcrService} remains the offline default.
 */
@ConfigurationProperties(prefix = "ocr.vision")
public class VisionOcrProperties {

    private boolean enabled = false;
    private String model = "gpt-4o";
    /** Vision-OCR page budget per PDF; pages beyond it fall back to Tesseract. */
    private int maxPages = 20;
    /** Rasterization DPI for scanned PDF pages. */
    private int dpi = 200;
    /**
     * Longest image edge sent to the model; larger images are downscaled
     * first. This is the upload size/cost control (Spring AI does not expose
     * OpenAI's per-image detail knob).
     */
    private int maxDimension = 2048;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public int getDpi() {
        return dpi;
    }

    public void setDpi(int dpi) {
        this.dpi = dpi;
    }

    public int getMaxDimension() {
        return maxDimension;
    }

    public void setMaxDimension(int maxDimension) {
        this.maxDimension = maxDimension;
    }
}
