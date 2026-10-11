package com.modocument.infrastructure.security.client;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Mutual-TLS client certificates (ws26) as the TLS-terminating edge reports
 * them. The AWS ALB (mTLS verify mode, per-bank trust store) forwards the
 * verified leaf as {@code X-Amzn-Mtls-Clientcert-Leaf} (URL-encoded PEM; in
 * passthrough mode the chain arrives as {@code X-Amzn-Mtls-Clientcert}); the
 * frontend nginx passes those headers on ONLY from the trusted ALB and strips
 * them otherwise (SE5, frontend/nginx.conf).
 *
 * <p><b>Trust.</b> Same model as X-Forwarded-For (SEC-022): the app trusts
 * exactly one hop — the proxy in front of it. Unlike X-Forwarded-For (which
 * Spring's ForwardedHeaderFilter consumes), these headers are read here, and
 * ONLY when the request's TCP peer — the socket address BEFORE the
 * forwarded-header rewrite — is inside
 * {@code modocument.integration.clients.mtls-trusted-proxy-cidrs}. Empty
 * (the default) means the headers are never trusted; a direct caller that
 * plants them is ignored (and, with no other credential, gets a 401).
 *
 * <p>The fingerprint is computed HERE from the certificate's DER (SHA-256,
 * lower-case hex) — no fingerprint header is trusted — and a certificate
 * outside its validity window is refused.
 */
@Component
public class MtlsClientCertificates {

    private static final Logger log = LoggerFactory.getLogger(MtlsClientCertificates.class);

    /** ALB verify mode: the verified leaf certificate (URL-encoded PEM). */
    public static final String HEADER_LEAF = "X-Amzn-Mtls-Clientcert-Leaf";
    /** ALB passthrough mode: the whole chain, leaf first (URL-encoded PEM). */
    public static final String HEADER_CHAIN = "X-Amzn-Mtls-Clientcert";

    private static final int MAX_HEADER_CHARS = 16 * 1024;

    private final List<IpAddressMatcher> trustedProxies;

    public MtlsClientCertificates(IntegrationClientProperties properties) {
        this.trustedProxies = properties.getClients().getMtlsTrustedProxyCidrs().stream()
                .map(String::trim).filter(cidr -> !cidr.isEmpty())
                .map(IpAddressMatcher::new).toList();
    }

    /** A verified client certificate the trusted proxy forwarded. */
    public record PresentedCertificate(String fingerprintSha256, String subject,
                                       X509Certificate certificate) {
    }

    /**
     * The client certificate of this request — empty when none was sent, when
     * the TCP peer is not a trusted proxy, or when the value is unusable.
     */
    public Optional<PresentedCertificate> presented(HttpServletRequest request) {
        String raw = firstNonBlank(request.getHeader(HEADER_LEAF), request.getHeader(HEADER_CHAIN));
        if (raw == null) {
            return Optional.empty();
        }
        if (!fromTrustedProxy(request)) {
            log.warn("Ignoring client-certificate headers from untrusted peer {}",
                    socketPeer(request));
            return Optional.empty();
        }
        if (raw.length() > MAX_HEADER_CHARS) {
            return Optional.empty();
        }
        try {
            X509Certificate certificate = parsePem(urlDecode(raw));
            certificate.checkValidity();
            return Optional.of(new PresentedCertificate(fingerprint(certificate),
                    certificate.getSubjectX500Principal().getName(), certificate));
        } catch (CertificateException | IllegalArgumentException unusable) {
            log.warn("Unusable client certificate from the trusted proxy: {}",
                    unusable.getMessage());
            return Optional.empty();
        }
    }

    /** Whether the socket peer (before X-Forwarded-For rewriting) is a trusted proxy. */
    public boolean fromTrustedProxy(HttpServletRequest request) {
        if (trustedProxies.isEmpty()) {
            return false;
        }
        String peer = socketPeer(request);
        if (peer == null || peer.isBlank()) {
            return false;
        }
        try {
            return trustedProxies.stream().anyMatch(matcher -> matcher.matches(peer));
        } catch (IllegalArgumentException notAnAddress) {
            return false;
        }
    }

    /**
     * The TCP peer: the innermost request's remote address. Spring's
     * ForwardedHeaderFilter (and Spring Security) wrap the request and
     * override getRemoteAddr with the forwarded client; the container's own
     * request underneath still reports the socket.
     */
    static String socketPeer(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current.getRemoteAddr();
    }

    /** Parses one PEM certificate (the first of a chain). */
    public static X509Certificate parsePem(String pem) throws CertificateException {
        if (pem == null || !pem.contains("-----BEGIN CERTIFICATE-----")) {
            throw new CertificateException("not a PEM certificate");
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.US_ASCII)));
    }

    /** SHA-256 over the certificate's DER encoding, lower-case hex. */
    public static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Percent-decoding only ('+' is base64, never a space here: the ALB and
     * nginx's $ssl_client_escaped_cert both percent-encode it).
     */
    static String urlDecode(String raw) {
        if (!raw.contains("%")) {
            return raw;
        }
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
