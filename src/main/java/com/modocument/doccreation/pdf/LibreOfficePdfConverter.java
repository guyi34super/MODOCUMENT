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
 * PDF conversion through headless LibreOffice ({@code soffice --convert-to
 * pdf}) — installed in the Docker image, optional in local dev where the
 * .docx download keeps working and PDF answers a clear 409. Each conversion
 * runs with its own LibreOffice user profile: the default profile takes a
 * lock, and concurrent conversions sharing it fail silently.
 */
@Component
public class LibreOfficePdfConverter implements PdfConverter {

    private static final Logger log = LoggerFactory.getLogger(LibreOfficePdfConverter.class);

    private final String sofficePath;
    private final long timeoutSeconds;
    private volatile Boolean available;

    public LibreOfficePdfConverter(
            @Value("${modocument.doccreation.soffice-path:soffice}") String sofficePath,
            @Value("${modocument.doccreation.soffice-timeout-seconds:120}") long timeoutSeconds) {
        this.sofficePath = sofficePath;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
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

    @Override
    public byte[] convert(Path docxFile) {
        if (!isAvailable()) {
            throw new IllegalStateException("PDF conversion is not available on this server "
                    + "(LibreOffice is not installed); download the .docx instead.");
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("modoc-pdf-");
            Path profileDir = Files.createDirectory(workDir.resolve("profile"));
            Path input = workDir.resolve(docxFile.getFileName());
            Files.copy(docxFile, input);

            // Output goes to a file, never a pipe we read inline: a wedged
            // soffice that keeps stdout open must not defeat waitFor's timeout
            // by blocking this thread in a read.
            Path outputLog = workDir.resolve("soffice.log");
            Process process = new ProcessBuilder(
                    sofficePath, "--headless", "--norestore",
                    "-env:UserInstallation=" + profileDir.toUri(),
                    "--convert-to", "pdf", "--outdir", workDir.toString(),
                    input.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(outputLog.toFile())
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("PDF conversion timed out.");
            }
            String output = readLog(outputLog);

            String pdfName = replaceExtension(input.getFileName().toString());
            Path pdf = workDir.resolve(pdfName);
            if (process.exitValue() != 0 || !Files.exists(pdf) || Files.size(pdf) == 0) {
                log.warn("LibreOffice conversion failed (exit {}): {}", process.exitValue(), output);
                // soffice answers exit 0 even when it cannot load the file —
                // typically libreoffice-core without the Writer component.
                if (output.contains("source file could not be loaded")) {
                    throw new IllegalStateException("PDF conversion failed: this LibreOffice "
                            + "installation cannot open Word documents (install libreoffice-writer); "
                            + "download the .docx instead.");
                }
                throw new IllegalStateException("PDF conversion failed; download the .docx instead.");
            }
            return Files.readAllBytes(pdf);
        } catch (IOException e) {
            throw new IllegalStateException("PDF conversion failed; download the .docx instead.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDF conversion was interrupted.", e);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private static String replaceExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName) + ".pdf";
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
