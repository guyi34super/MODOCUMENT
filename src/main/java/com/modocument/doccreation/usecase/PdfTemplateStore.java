package com.modocument.doccreation.usecase;

import com.modocument.application.port.FileStore;
import com.modocument.doccreation.pdftemplate.PdfTemplateEngine;
import com.modocument.domain.model.Hashing;
import com.modocument.infrastructure.file.store.FileStoreKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Locale;

/**
 * Stored files of the exact PDF templates, through the {@link FileStore}
 * port, under bank-scoped keys (the database rows that reference a key carry
 * the tenant boundary; a key alone never grants access):
 * <ul>
 *   <li>{@code doccreation/b<bank>/blueprint<id>/original.pdf}: the upload, unchanged</li>
 *   <li>{@code …/template-v<N>-<nonce>.pdf}: a version's template PDF</li>
 *   <li>{@code …/draft.json}: review data of a DRAFT (removed sample values),
 *       deleted on confirm</li>
 *   <li>{@code …/edit-images/<imageKey>}: images for ADD_IMAGE edits</li>
 *   <li>{@code …/pages/<sha256>-p<page>-d<dpi>.png}: rendered page cache, keyed
 *       by the PDF's content hash, so it can never serve a stale page</li>
 * </ul>
 * A template's files share its prefix, so deleting a template removes them
 * all. Also the access point to the {@link PdfTemplateEngine}: resolved
 * lazily, so the application starts even where no engine bean is wired.
 */
@Component
public class PdfTemplateStore {

    private static final Logger log = LoggerFactory.getLogger(PdfTemplateStore.class);

    /** The DPI the editor and builder previews use when none is asked for. */
    public static final int DEFAULT_DPI = 110;
    static final int MIN_DPI = 36;
    static final int MAX_DPI = 200;

    private final FileStore files;
    private final ObjectProvider<PdfTemplateEngine> engine;

    public PdfTemplateStore(FileStore files, ObjectProvider<PdfTemplateEngine> engine) {
        this.files = files;
        this.engine = engine;
    }

    /** The engine, or a 503 when this build has none. */
    public PdfTemplateEngine engine() {
        PdfTemplateEngine available = engine.getIfAvailable();
        if (available == null) {
            throw new DocCreationException(503, DocCreationException.ENGINE_UNAVAILABLE,
                    "The PDF template engine is not available on this server.");
        }
        return available;
    }

    public FileStore files() {
        return files;
    }

    // ------------------------------------------------------------------ keys

    public static String blueprintPrefix(int bankId, int blueprintId) {
        return "doccreation/b" + bankId + "/blueprint" + blueprintId + "/";
    }

    public static String originalKey(int bankId, int blueprintId) {
        return FileStoreKeys.validate(blueprintPrefix(bankId, blueprintId) + "original.pdf");
    }

    /** A fresh key per build: a rolled-back save never overwrites the live template. */
    public static String templateKey(int bankId, int blueprintId, int version) {
        String nonce = java.util.UUID.randomUUID().toString().substring(0, 8);
        return FileStoreKeys.validate(blueprintPrefix(bankId, blueprintId)
                + "template-v" + version + "-" + nonce + ".pdf");
    }

    public static String draftKey(int bankId, int blueprintId) {
        return FileStoreKeys.validate(blueprintPrefix(bankId, blueprintId) + "draft.json");
    }

    public static String editImageKey(int bankId, int blueprintId, String imageKey) {
        return FileStoreKeys.validate(blueprintPrefix(bankId, blueprintId) + "edit-images/" + imageKey);
    }

    static String pageCacheKey(int bankId, int blueprintId, String sha256, int page, int dpi) {
        return FileStoreKeys.validate(blueprintPrefix(bankId, blueprintId) + "pages/" + sha256
                + "-p" + page + "-d" + dpi + ".png");
    }

    // ------------------------------------------------------------------ I/O

    public byte[] read(String key) {
        try (InputStream in = files.open(key)) {
            return in.readAllBytes();
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("A stored template file is missing (" + key
                    + "); upload the template again.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read a stored template file.", e);
        }
    }

    public void write(String key, byte[] bytes, String contentType) {
        try {
            files.put(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store a template file.", e);
        }
    }

    public boolean exists(String key) {
        try {
            return files.exists(key);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not check a stored file.", e);
        }
    }

    /** Every stored key of one template (its prefix). */
    public List<String> listBlueprintFiles(int bankId, int blueprintId) {
        try {
            return files.list(blueprintPrefix(bankId, blueprintId));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list the template's files.", e);
        }
    }

    // ------------------------------------------------------------- previews

    public static int clampDpi(Integer dpi) {
        int value = dpi == null ? DEFAULT_DPI : dpi;
        return Math.max(MIN_DPI, Math.min(MAX_DPI, value));
    }

    /**
     * A page of a template's PDF as PNG, served from the cache keyed by
     * sha256(pdf) + page + dpi, rendered and cached on a miss. A cache write
     * that fails only costs the next request a render.
     */
    public byte[] renderCached(int bankId, int blueprintId, byte[] pdf, int page, int dpi) {
        String key = pageCacheKey(bankId, blueprintId, Hashing.sha256(pdf), page, dpi);
        try {
            if (files.exists(key)) {
                try (InputStream in = files.open(key)) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException e) {
            log.warn("Page cache read failed for {}; rendering", key, e);
        }
        byte[] png = engine().renderPage(pdf, page, dpi);
        try {
            files.put(key, new ByteArrayInputStream(png), png.length, "image/png");
        } catch (IOException | RuntimeException e) {
            log.warn("Could not cache rendered page {}", key, e);
        }
        return png;
    }

    /** True when the bytes start with the PDF header {@code %PDF-} (leading whitespace allowed). */
    public static boolean looksLikePdf(byte[] bytes) {
        int i = 0;
        while (i < bytes.length && i < 16 && (bytes[i] == ' ' || bytes[i] == '\n' || bytes[i] == '\r'
                || bytes[i] == '\t' || bytes[i] == '\f' || bytes[i] == 0)) {
            i++;
        }
        byte[] magic = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (bytes.length - i < magic.length) {
            return false;
        }
        for (int j = 0; j < magic.length; j++) {
            if (bytes[i + j] != magic[j]) {
                return false;
            }
        }
        return true;
    }

    /** A safe download name: no path characters, ends with the extension. */
    public static String downloadName(String name, String extension) {
        String cleaned = DocInstanceUseCase.sanitizeFileName(name);
        String lower = cleaned.toLowerCase(Locale.ROOT);
        for (String old : List.of(".pdf", ".docx")) {
            if (lower.endsWith(old)) {
                cleaned = cleaned.substring(0, cleaned.length() - old.length());
                break;
            }
        }
        return (cleaned.isBlank() ? "document" : cleaned) + extension;
    }
}
