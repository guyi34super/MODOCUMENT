package com.modocument.doccreation.pdf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * PDF blueprint import: headless LibreOffice ({@code soffice --infilter=writer_pdf_import --convert-to docx}).
 * Installed in the Docker image; optional in local dev — PDF blueprints are
 * rejected with a clear message when soffice is missing. Uses an isolated user
 * profile per conversion (same concurrency model as {@link LibreOfficePdfConverter}).
 */
@Component
public class LibreOfficeDocxConverter {

    private static final Logger log = LoggerFactory.getLogger(LibreOfficeDocxConverter.class);

    private final String sofficePath;
    private final long timeoutSeconds;
    private volatile Boolean available;

    public LibreOfficeDocxConverter(
            @Value("${modocument.doccreation.soffice-path:soffice}") String sofficePath,
            @Value("${modocument.doccreation.soffice-timeout-seconds:120}") long timeoutSeconds) {
        this.sofficePath = sofficePath;
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean probed = probe();
        available = probed;
        return probed;
    }

    private boolean probe() {
        try {
            Process process = new ProcessBuilder(sofficePath, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * @return the converted .docx bytes
     * @throws IllegalStateException when LibreOffice is unavailable or conversion fails
     */
    public byte[] convertPdfToDocx(Path pdfFile) {
        if (!isAvailable()) {
            throw new IllegalStateException("PDF blueprint upload requires LibreOffice on this server "
                    + "(soffice is not installed); upload a .docx blueprint instead.");
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("modoc-docx-");
            Path profileDir = Files.createDirectory(workDir.resolve("profile"));
            Path input = workDir.resolve(pdfFile.getFileName());
            Files.copy(pdfFile, input);

            Path outputLog = workDir.resolve("soffice.log");
            // PDF input requires writer_pdf_import; without it soffice often logs
            // "source file could not be loaded" even for valid text PDFs.
            Process process = new ProcessBuilder(
                    sofficePath, "--headless", "--norestore",
                    "-env:UserInstallation=" + profileDir.toUri(),
                    "--infilter=writer_pdf_import",
                    "--convert-to", "docx", "--outdir", workDir.toString(),
                    input.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(outputLog.toFile())
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("PDF to Word conversion timed out.");
            }
            String output = readLog(outputLog);

            String docxName = replaceExtension(input.getFileName().toString(), ".docx");
            Path docx = workDir.resolve(docxName);
            if (process.exitValue() != 0 || !Files.exists(docx) || Files.size(docx) == 0) {
                log.warn("LibreOffice PDF→docx failed (exit {}): {}", process.exitValue(), output);
                if (output.contains("source file could not be loaded")) {
                    throw new IllegalStateException("Could not convert this PDF (LibreOffice could not "
                            + "open it); try a text-based PDF or upload .docx instead.");
                }
                throw new IllegalStateException("Could not convert PDF to Word; upload a .docx "
                        + "blueprint instead.");
            }
            return Files.readAllBytes(docx);
        } catch (IOException e) {
            throw new IllegalStateException("Could not convert PDF to Word; upload a .docx blueprint "
                    + "instead.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDF to Word conversion was interrupted.", e);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private static String replaceExtension(String fileName, String newExt) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName) + newExt;
    }

    private static String readLog(Path outputLog) {
        try {
            String text = Files.readString(outputLog);
            return text.length() > 2000 ? text.substring(0, 2000) : text;
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
