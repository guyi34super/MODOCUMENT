package com.modocument.infrastructure.file;

import com.modocument.infrastructure.ai.AiFilesProperties;
import com.modocument.infrastructure.ai.AiFilesServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * OCR via the configured OpenAI vision model. Highest-quality tier of the
 * built-in OCR chain: tried first for standalone images and scanned PDF
 * pages, with the Tesseract {@link OcrService} as offline fallback. Enabled
 * only when a real OpenAI api-key is configured, and degrades to returning no
 * text on any failure instead of failing the whole transform.
 */
@Component
public class VisionOcrService {

    static final String OCR_PROMPT = """
            Transcribe ALL text visible in this image exactly as written.
            Preserve reading order and line breaks; render tables as GitHub markdown tables.
            Output only the transcribed text - no commentary, no code fences.
            If the image contains no text, output nothing.""";

    private static final Logger log = LoggerFactory.getLogger(VisionOcrService.class);

    private final ChatClient chatClient;
    private final VisionOcrProperties properties;
    private final AiFilesProperties aiProperties;

    public VisionOcrService(ChatClient chatClient,
                            VisionOcrProperties properties,
                            AiFilesProperties aiProperties) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.aiProperties = aiProperties;
    }

    /** True when vision OCR is switched on AND a real (non-sentinel) OpenAI key is configured. */
    public boolean isEnabled() {
        String apiKey = aiProperties.getApiKey();
        return properties.isEnabled()
                && apiKey != null
                && !apiKey.isBlank()
                && !AiFilesServiceImpl.UNSET_API_KEY.equals(apiKey);
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
            var prompt = chatClient.prompt();
            String model = properties.getModel();
            if (model != null && !model.isBlank()) {
                prompt = prompt.options(OpenAiChatOptions.builder().model(model).build());
            }
            String text = prompt
                    .user(u -> u.text(OCR_PROMPT)
                            .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(png))))
                    .call()
                    .content();
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
