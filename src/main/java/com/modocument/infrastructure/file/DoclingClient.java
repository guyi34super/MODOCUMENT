package com.modocument.infrastructure.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Client for a docling-serve instance: converts pdf/office/csv/image documents
 * to markdown (with OCR for scans) via POST {base-url}/v1/convert/file.
 */
@Component
public class DoclingClient {

    /** Formats docling-serve accepts; anything else goes to the local readers. */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".doc", ".pptx", ".xlsx", ".xls", ".csv", ".html", ".md",
            ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DoclingProperties properties;
    private final RestClient restClient;

    public DoclingClient(DoclingProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public boolean supports(String extension) {
        return extension != null
                && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    public Set<String> supportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    /**
     * Converts the document to markdown. Throws on any transport/HTTP/JSON
     * problem — callers decide whether to fall back to the local readers.
     */
    public String convertToMarkdown(Path file) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("files", new FileSystemResource(file));
        form.add("to_formats", "md");
        form.add("do_ocr", "true");

        String body = restClient.post()
                .uri(properties.getBaseUrl().replaceAll("/+$", "") + properties.getConvertPath())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            JsonNode content = root.path("document").path("md_content");
            if (content.isMissingNode() || content.isNull()) {
                throw new IllegalStateException("docling response contained no document.md_content");
            }
            return content.asText();
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse docling response.", e);
        }
    }
}
