package com.modocument.infrastructure.file;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;

/**
 * Magic-byte gate for ingestion uploads (SEC-030): the file's CONTENT has to
 * match what its extension claims, so a renamed executable can never enter
 * the pipeline as a ".csv" or ".xlsx". The extension allowlist
 * ({@link SupportedDocumentTypes}) runs first and answers which formats exist
 * at all; this check runs second and answers whether the bytes are plausibly
 * that format:
 *
 * <ul>
 *   <li>ZIP-based Office formats (xlsx/xlsm/docx/pptx) must start with the
 *       ZIP local-file signature ("PK");</li>
 *   <li>legacy OLE2 Office formats (doc/xls) must start with the compound
 *       file header (D0 CF 11 E0 A1 B1 1A E1);</li>
 *   <li>PDF must carry "%PDF" within its first kilobyte (the spec allows a
 *       short preamble before the header);</li>
 *   <li>images must start with their format signature (PNG/JPEG/GIF/BMP/
 *       TIFF/WEBP) — same bytes-over-name stance as
 *       DocInstanceUseCase#sniffImageContentType;</li>
 *   <li>plain-text formats (csv/txt/html) are checked permissively: no NUL
 *       byte in the first 8 KB. Any real text encoding passes; compiled
 *       binaries do not.</li>
 * </ul>
 *
 * <p>Extensions this class has no signature knowledge of (e.g. formats a
 * configured docling endpoint adds) pass through unchecked — the allowlist
 * already bounded them.
 *
 * @throws IllegalArgumentException surfaced as 400 by the controllers'
 *     handlers.
 */
@Component
public class UploadContentChecker {

    /** How much of the head is inspected; covers every signature + text scan. */
    static final int HEAD_LENGTH = 8192;

    private static final byte[] OLE2 = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF);
    private static final byte[] GIF = bytes('G', 'I', 'F', '8');
    private static final byte[] BMP = bytes('B', 'M');
    private static final byte[] TIFF_LE = bytes('I', 'I', 0x2A, 0x00);
    private static final byte[] TIFF_BE = bytes('M', 'M', 0x00, 0x2A);
    private static final byte[] PDF = bytes('%', 'P', 'D', 'F');
    private static final byte[] RIFF = bytes('R', 'I', 'F', 'F');
    private static final byte[] WEBP = bytes('W', 'E', 'B', 'P');

    /** Verifies the upload's content matches its claimed extension. */
    public void requireContentMatchesExtension(String fileName, MultipartFile file) {
        requireContentMatchesExtension(fileName, head(file));
    }

    /** Same check against an already-read head (first {@link #HEAD_LENGTH} bytes). */
    public void requireContentMatchesExtension(String fileName, byte[] head) {
        String extension = extensionOf(fileName);
        boolean matches = switch (extension) {
            case "xlsx", "xlsm", "docx", "pptx" -> startsWith(head, (byte) 'P', (byte) 'K');
            case "xls", "doc" -> startsWith(head, OLE2);
            case "pdf" -> indexOf(head, PDF, 1024) >= 0;
            case "png" -> startsWith(head, PNG);
            case "jpg", "jpeg" -> startsWith(head, JPEG);
            case "gif" -> startsWith(head, GIF);
            case "bmp" -> startsWith(head, BMP);
            case "tif", "tiff" -> startsWith(head, TIFF_LE) || startsWith(head, TIFF_BE);
            case "webp" -> startsWith(head, RIFF) && indexOf(head, WEBP, 16) == 8;
            case "csv", "txt", "html", "htm" -> looksLikeText(head);
            default -> true; // No signature knowledge — the allowlist bounded it.
        };
        if (!matches) {
            throw new IllegalArgumentException("The file's content does not match its ."
                    + extension + " extension.");
        }
    }

    private static byte[] head(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEAD_LENGTH);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file.", e);
        }
    }

    /** Permissive text probe: an empty head is fine, a NUL byte is binary. */
    private static boolean looksLikeText(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] head, byte... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (head[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /** First occurrence of {@code needle} within the head's first {@code limit} bytes. */
    private static int indexOf(byte[] head, byte[] needle, int limit) {
        int end = Math.min(head.length, limit) - needle.length;
        outer:
        for (int i = 0; i <= end; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (head[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
