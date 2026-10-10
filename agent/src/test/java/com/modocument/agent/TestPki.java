package com.modocument.agent;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Throwaway TLS identities for the agent's mTLS tests, made per run with the
 * JDK's keytool (self-signed, 2 days) — nothing is committed.
 */
final class TestPki {

    private static final char[] PASSWORD = "changeit".toCharArray();

    /** One identity: its key store and the PEMs the agent's local secret would hold. */
    record Identity(KeyStore store, String alias, String certificatePem, String privateKeyPem) {
        Certificate certificate() throws Exception {
            return store.getCertificate(alias);
        }
    }

    private TestPki() {
    }

    static Identity create(Path dir, String alias, String dname, String san) throws Exception {
        Path file = dir.resolve(alias + ".p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        List<String> command = new ArrayList<>(List.of(keytool, "-genkeypair", "-alias", alias,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", dname,
                "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", "changeit", "-keypass", "changeit"));
        if (san != null) {
            command.add("-ext");
            command.add(san);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(file.toFile())) {
            store.load(in, PASSWORD);
        }
        return new Identity(store, alias,
                pem("CERTIFICATE", store.getCertificate(alias).getEncoded()),
                pem("PRIVATE KEY", ((PrivateKey) store.getKey(alias, PASSWORD)).getEncoded()));
    }

    /** The fake MoDocument's server context: its own key, trusting exactly {@code clients}. */
    static SSLContext server(Identity server, Identity... clients) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(server.store(), PASSWORD);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        for (Identity client : clients) {
            trust.setCertificateEntry(client.alias(), client.certificate());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return context;
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
