package com.modocument.infrastructure.integration.connector;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;

/**
 * In-memory TLS material for MTLS outbound auth (ws25): the client
 * certificate chain and PKCS#8 private key come from the connection's vault
 * secret ({@code clientCertPem}, {@code clientKeyPem}), an optional private
 * CA from the connection config ({@code caCertPem}, not secret). Nothing
 * touches the filesystem; the transient key store password is random.
 */
public final class TlsMaterial {

    private TlsMaterial() {
    }

    public static SSLContext sslContext(String clientCertPem, String clientKeyPem, String caCertPem) {
        try {
            KeyManagerFactory keyManagers = null;
            if (clientCertPem != null && clientKeyPem != null) {
                Certificate[] chain = certificates(clientCertPem).toArray(new Certificate[0]);
                if (chain.length == 0) {
                    throw new IllegalArgumentException("The client certificate PEM holds no certificate.");
                }
                PrivateKey key = privateKey(clientKeyPem);
                char[] password = randomPassword();
                KeyStore store = KeyStore.getInstance("PKCS12");
                store.load(null, null);
                store.setKeyEntry("client", key, password, chain);
                keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(store, password);
            }
            TrustManagerFactory trustManagers = null;
            if (caCertPem != null && !caCertPem.isBlank()) {
                KeyStore trust = KeyStore.getInstance("PKCS12");
                trust.load(null, null);
                int i = 0;
                for (Certificate ca : certificates(caCertPem)) {
                    trust.setCertificateEntry("ca" + i++, ca);
                }
                trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagers.init(trust);
            }
            // TLSv1.3 context: negotiates TLS 1.3, falls back to TLS 1.2 for
            // servers without 1.3; SSL and TLS 1.0/1.1 are never offered.
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(keyManagers == null ? null : keyManagers.getKeyManagers(),
                    trustManagers == null ? null : trustManagers.getTrustManagers(),
                    new SecureRandom());
            return context;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // Never echo the PEM content.
            throw new IllegalArgumentException("The TLS client certificate or key could not be "
                    + "loaded (" + e.getClass().getSimpleName() + ").");
        }
    }

    public static Collection<? extends Certificate> certificates(String pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return factory.generateCertificates(
                new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.US_ASCII)));
    }

    /** A PKCS#8 ("BEGIN PRIVATE KEY") RSA or EC key. */
    public static PrivateKey privateKey(String pem) throws Exception {
        if (pem.contains("BEGIN RSA PRIVATE KEY") || pem.contains("BEGIN EC PRIVATE KEY")
                || pem.contains("ENCRYPTED PRIVATE KEY")) {
            throw new IllegalArgumentException("The client key must be an unencrypted PKCS#8 PEM "
                    + "(-----BEGIN PRIVATE KEY-----); convert it with "
                    + "'openssl pkcs8 -topk8 -nocrypt'.");
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : new String[] {"RSA", "EC", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception tryNext) {
                // next algorithm
            }
        }
        throw new IllegalArgumentException("The client key is not an RSA, EC or Ed25519 key.");
    }

    private static char[] randomPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes).toCharArray();
    }
}
