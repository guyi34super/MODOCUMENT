package com.modocument.infrastructure.file;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Answers, at upload time, whether the pipeline can parse a file — so users
 * get an immediate 400 with the supported-type list instead of a job that
 * fails later at the parse stage. Docling-aware: extra formats appear when a
 * docling endpoint is configured.
 */
@Component
public class SupportedDocumentTypes {

    private final TabularNormalizer tabularNormalizer;
    private final CompositeDocumentParser documentParser;

    public SupportedDocumentTypes(TabularNormalizer tabularNormalizer,
                                  CompositeDocumentParser documentParser) {
        this.tabularNormalizer = tabularNormalizer;
        this.documentParser = documentParser;
    }

    public boolean isSupported(String fileName) {
        String extension = extensionOf(fileName);
        return tabularNormalizer.supports(extension) || documentParser.supports(extension);
    }

    /**
     * Sorted extensions with their leading dot, e.g. {@code [".csv", ".pdf"]} —
     * exactly what a file input's {@code accept} attribute needs, so the
     * picker can never offer a format this build would reject.
     */
    public Set<String> supportedExtensions() {
        Set<String> extensions = new TreeSet<>(tabularNormalizer.supportedExtensions());
        extensions.addAll(documentParser.supportedExtensions());
        return extensions.stream()
                .map(ext -> ext.startsWith(".") ? ext : "." + ext)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Sorted, dot-less list for error messages, e.g. "csv, doc, docx, …". */
    public String describeSupported() {
        Set<String> extensions = new TreeSet<>(tabularNormalizer.supportedExtensions());
        extensions.addAll(documentParser.supportedExtensions());
        return extensions.stream()
                .map(ext -> ext.startsWith(".") ? ext.substring(1) : ext)
                .collect(Collectors.joining(", "));
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
