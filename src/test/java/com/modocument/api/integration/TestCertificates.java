package com.modocument.api.integration;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Self-signed client certificates for the mutual-TLS tests, made with the
 * JDK's own keytool (no certificate-building library on the classpath).
 */
public final class TestCertificates {

    private TestCertificates() {
    }

    /** A fresh self-signed certificate for {@code CN=<commonName>}, as PEM. */
    public static String selfSignedPem(String commonName) {
        try {
            Path dir = Files.createTempDirectory("modocument-cert");
            Path store = dir.resolve("client.p12");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process process = new ProcessBuilder(List.of(keytool, "-genkeypair",
                    "-alias", "client", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-dname", "CN=" + commonName + ",O=Test Bank", "-validity", "30",
                    "-storetype", "PKCS12", "-keystore", store.toString(),
                    "-storepass", "changeit", "-keypass", "changeit"))
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool failed");
            }
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(store.toFile())) {
                keyStore.load(in, "changeit".toCharArray());
            }
            Certificate certificate = keyStore.getCertificate("client");
            Files.deleteIfExists(store);
            Files.deleteIfExists(dir);
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IllegalStateException("Could not create a test certificate", e);
        }
    }

    /** The PEM as the ALB forwards it (percent-encoded; space = %20, '+' = %2B). */
    public static String headerValue(String pem) {
        return URLEncoder.encode(pem, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
