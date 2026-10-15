package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Loading with the engine's refusal codes, and deterministic saving. */
final class PdfDocuments {

    static final int MAX_PAGES = 500;
    /** Above this size the parser caches streams in a temp file instead of the heap. */
    static final int TEMP_FILE_THRESHOLD = 16 * 1024 * 1024;

    private static final byte[] MAGIC = {'%', 'P', 'D', 'F', '-'};

    private PdfDocuments() {
    }

    static boolean hasPdfMagic(byte[] pdf) {
        if (pdf == null || pdf.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (pdf[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Opens a PDF or throws {@link PdfTemplateException} with NOT_A_PDF, ENCRYPTED, TOO_MANY_PAGES or
     * UNREADABLE. Files that are encrypted but open with the empty user password are accepted, and their
     * encryption is dropped on save.
     */
    static PDDocument load(byte[] pdf) {
        if (!hasPdfMagic(pdf)) {
            throw new PdfTemplateException("NOT_A_PDF", "The file is not a PDF.");
        }
        PDDocument doc;
        try {
            doc = pdf.length > TEMP_FILE_THRESHOLD
                    ? Loader.loadPDF(new RandomAccessReadBuffer(pdf), "", null, null,
                            MemoryUsageSetting.setupTempFileOnly().streamCache)
                    : Loader.loadPDF(pdf, "");
        } catch (InvalidPasswordException e) {
            throw new PdfTemplateException("ENCRYPTED", "The PDF is password-protected.", e);
        } catch (IOException | RuntimeException e) {
            throw new PdfTemplateException("UNREADABLE", "The PDF could not be read.", e);
        }
        int pages;
        try {
            pages = doc.getNumberOfPages();
        } catch (RuntimeException e) {
            closeQuietly(doc);
            throw new PdfTemplateException("UNREADABLE", "The PDF page tree could not be read.", e);
        }
        if (pages > MAX_PAGES) {
            closeQuietly(doc);
            throw new PdfTemplateException("TOO_MANY_PAGES",
                    "The PDF has " + pages + " pages; at most " + MAX_PAGES + " are supported.");
        }
        if (pages == 0) {
            closeQuietly(doc);
            throw new PdfTemplateException("UNREADABLE", "The PDF has no pages.");
        }
        if (doc.isEncrypted()) {
            doc.setAllSecurityToBeRemoved(true);
        }
        return doc;
    }

    static List<PageFrame> frames(PDDocument doc) {
        List<PageFrame> frames = new ArrayList<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            frames.add(PageFrame.of(i, doc.getPage(i)));
        }
        return frames;
    }

    /** Saves with a document id derived from {@code seed}, so equal inputs give equal bytes. */
    static byte[] save(PDDocument doc, byte[] seed) {
        doc.setDocumentId(ByteBuffer.wrap(sha256(seed)).getLong());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            doc.save(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the PDF.", e);
        }
        return out.toByteArray();
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                if (part != null) {
                    md.update(part);
                }
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void closeQuietly(PDDocument doc) {
        if (doc == null) {
            return;
        }
        try {
            doc.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
