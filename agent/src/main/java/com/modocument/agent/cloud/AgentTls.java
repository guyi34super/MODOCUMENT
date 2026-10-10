package com.modocument.agent.cloud;

import com.modocument.agent.secrets.LocalSecrets.Secret;

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
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * TLS towards MoDocument (agent/README.md "Authentication"):
 * <ul>
 *   <li><b>mutual TLS</b>: the agent's client certificate and key come from
 *       its LOCAL secret store (the secret named by
 *       {@code MODOCUMENT_AGENT_CLIENT_CERT_REF}, keys {@code certificatePem}
 *       and {@code privateKeyPem}). The certificate goes to MoDocument at
 *       enrolment; the token endpoint then authenticates the agent by it and
 *       binds every access token to it (RFC 8705), so every call presents
 *       it;</li>
 *   <li><b>private CA</b> ({@code MODOCUMENT_AGENT_TRUSTED_CA_FILE}): a PEM
 *       bundle that REPLACES the JDK trust store for calls to MoDocument
 *       (local development, a TLS-inspecting proxy).</li>
 * </ul>
 * Everything stays in memory; the transient key store password is random.
 * Errors never echo PEM content.
 */
public final class AgentTls {

    /** Local-secret key of the client certificate (PEM, leaf first). */
    public static final String CERTIFICATE_PEM = "certificatePem";
    /** Local-secret key of the client's unencrypted PKCS#8 private key (PEM). */
    public static final String PRIVATE_KEY_PEM = "privateKeyPem";

    private AgentTls() {
    }

    /** How the agent authenticates to MoDocument: the method and, for MTLS, its certificate. */
    public record ClientAuth(String method, String certificatePem) {

        public static final String OAUTH2 = "OAUTH2_CLIENT_CREDENTIALS";
        public static final String MTLS = "MTLS";

        public static ClientAuth oauth2() {
            return new ClientAuth(OAUTH2, null);
        }

        public boolean mtls() {
            return MTLS.equals(method);
        }

        /** OAUTH2_CLIENT_CREDENTIALS (also: empty, OAUTH2) or MTLS. */
        public static String normalize(String method) {
            if (method == null || method.isBlank()) {
                return OAUTH2;
            }
            String upper = method.strip().toUpperCase(Locale.ROOT);
            return switch (upper) {
                case "OAUTH2", OAUTH2 -> OAUTH2;
                case MTLS -> MTLS;
                default -> throw new IllegalStateException("MODOCUMENT_AGENT_AUTH_METHOD must be "
                        + OAUTH2 + " or " + MTLS + ".");
            };
        }

        @Override
        public String toString() {
            return "ClientAuth[" + method + "]";
        }
    }

    /** The certificate and key of the local secret, checked before any call. */
    public record ClientCertificate(String certificatePem, String privateKeyPem) {

        public static ClientCertificate from(Secret secret, String ref) {
            String certificate = secret.values().get(CERTIFICATE_PEM);
            String key = secret.values().get(PRIVATE_KEY_PEM);
            if (certificate == null || certificate.isBlank() || key == null || key.isBlank()) {
                throw new IllegalStateException("The local secret '" + ref + "' must hold '"
                        + CERTIFICATE_PEM + "' and '" + PRIVATE_KEY_PEM + "' for MTLS.");
            }
            return new ClientCertificate(certificate.strip() + "\n", key);
        }

        @Override
        public String toString() {
            return "ClientCertificate[(values redacted)]";
        }
    }

    /**
     * The SSLContext for calls to MoDocument, or null when neither a client
     * certificate nor a private CA is configured (the JDK default then applies).
     */
    public static SSLContext sslContext(ClientCertificate client, String trustedCaPem) {
        if (client == null && (trustedCaPem == null || trustedCaPem.isBlank())) {
            return null;
        }
        try {
            KeyManagerFactory keyManagers = null;
            if (client != null) {
                List<Certificate> chain = List.copyOf(certificates(client.certificatePem()));
                if (chain.isEmpty()) {
                    throw new IllegalStateException("The agent's client certificate PEM holds no certificate.");
                }
                ((X509Certificate) chain.get(0)).checkValidity();
                PrivateKey key = privateKey(client.privateKeyPem());
                char[] password = randomPassword();
                KeyStore store = KeyStore.getInstance("PKCS12");
                store.load(null, null);
                store.setKeyEntry("agent", key, password, chain.toArray(new Certificate[0]));
                keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(store, password);
            }
            TrustManagerFactory trustManagers = null;
            if (trustedCaPem != null && !trustedCaPem.isBlank()) {
                KeyStore trust = KeyStore.getInstance("PKCS12");
                trust.load(null, null);
                int i = 0;
                for (Certificate ca : certificates(trustedCaPem)) {
                    trust.setCertificateEntry("ca" + i++, ca);
                }
                if (i == 0) {
                    throw new IllegalStateException("The trusted CA file holds no certificate.");
                }
                trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagers.init(trust);
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers == null ? null : keyManagers.getKeyManagers(),
                    trustManagers == null ? null : trustManagers.getTrustManagers(),
                    new SecureRandom());
            return context;
        } catch (IllegalStateException e) {
            throw e;
        } catch (CertificateException e) {
            throw new IllegalStateException("The agent's TLS certificate is unusable or outside its "
                    + "validity period (" + e.getClass().getSimpleName() + ").");
        } catch (Exception e) {
            throw new IllegalStateException("The agent's TLS material could not be loaded ("
                    + e.getClass().getSimpleName() + ").");
        }
    }

    static Collection<? extends Certificate> certificates(String pem) throws CertificateException {
        if (pem == null || !pem.contains("-----BEGIN CERTIFICATE-----")) {
            throw new CertificateException("not a PEM certificate");
        }
        return CertificateFactory.getInstance("X.509").generateCertificates(
                new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.US_ASCII)));
    }

    /** An unencrypted PKCS#8 ("BEGIN PRIVATE KEY") RSA, EC or Ed25519 key. */
    static PrivateKey privateKey(String pem) {
        if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
            throw new IllegalStateException("The agent's private key must be an unencrypted PKCS#8 "
                    + "PEM (-----BEGIN PRIVATE KEY-----); convert it with "
                    + "'openssl pkcs8 -topk8 -nocrypt'.");
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                    .replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The agent's private key is not valid PEM.");
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : new String[] {"RSA", "EC", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception tryNext) {
                // next algorithm
            }
        }
        throw new IllegalStateException("The agent's private key is not an RSA, EC or Ed25519 key.");
    }

    private static char[] randomPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes).toCharArray();
    }
}
