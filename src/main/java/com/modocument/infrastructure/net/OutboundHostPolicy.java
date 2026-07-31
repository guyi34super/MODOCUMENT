package com.modocument.infrastructure.net;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Server-Side Request Forgery guard (F-003). Every outbound target a user can
 * influence — a database export URL, an extraction source's API base URL, a
 * crawl URL — is resolved and checked against this policy before the app
 * connects. Hosts that resolve to loopback, private, link-local (including the
 * cloud metadata address 169.254.169.254), unique-local, or carrier-grade-NAT
 * ranges are refused, so a user cannot turn the server into a proxy for its own
 * internal network.
 *
 * <p>All resolved addresses must pass, which also blunts DNS rebinding: a name
 * that resolves to both a public and an internal address is rejected. (A TOCTOU
 * gap remains between this check and the socket connect, and auto-followed HTTP
 * redirects are not re-checked hop-by-hop — noted as residual; the primary
 * control is refusing an internal *target*.)
 *
 * <p>Disabled or host-allow-listed via {@code modocument.security.ssrf.*};
 * tests that talk to a loopback server set {@code enabled=false}.
 */
@Component
public class OutboundHostPolicy {

    private final boolean enabled;
    private final Set<String> allowHosts;

    public OutboundHostPolicy(
            @Value("${modocument.security.ssrf.enabled:true}") boolean enabled,
            @Value("${modocument.security.ssrf.allow-hosts:}") String[] allowHosts) {
        this.enabled = enabled;
        this.allowHosts = new HashSet<>();
        if (allowHosts != null) {
            for (String host : allowHosts) {
                if (host != null && !host.isBlank()) {
                    this.allowHosts.add(host.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    /** Enforcing policy with no allow-list — for tests that assert the blocking. */
    public static OutboundHostPolicy enforcing() {
        return new OutboundHostPolicy(true, new String[0]);
    }

    /** Off — for tests that legitimately talk to a loopback server. */
    public static OutboundHostPolicy disabled() {
        return new OutboundHostPolicy(false, new String[0]);
    }

    /** @throws IllegalArgumentException if the URL's host is not an allowed egress target. */
    public void checkUrl(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL.");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL has no host.");
        }
        checkHost(host);
    }

    /**
     * Checks the host embedded in a JDBC URL (e.g. jdbc:postgresql://host:5432/db).
     * URLs with no network host (jdbc:h2:mem:…) have nothing to reach and pass.
     */
    public void checkJdbcUrl(String jdbcUrl) {
        String host = jdbcHost(jdbcUrl);
        if (host != null && !host.isBlank()) {
            checkHost(host);
        }
    }

    /** @throws IllegalArgumentException if the host resolves to a non-routable/internal address. */
    public void checkHost(String host) {
        if (!isHostAllowed(host)) {
            throw new IllegalArgumentException(
                    "The target host is not an allowed destination (it resolves to a private or "
                            + "internal address).");
        }
    }

    /** Non-throwing form for the crawl gate, which turns a denial into a terminal skip. */
    public boolean isHostAllowed(String host) {
        if (!enabled) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (allowHosts.contains(normalized)) {
            return true;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // Can't verify where it points — refuse rather than risk it.
            return false;
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                return false;
            }
        }
        return addresses.length > 0;
    }

    private static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            // Unique-local addresses fc00::/7 (Java has no predicate for these).
            return (bytes[0] & 0xfe) == 0xfc;
        }
        // Carrier-grade NAT 100.64.0.0/10.
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 100 && second >= 64 && second <= 127;
    }

    /** Host portion of a jdbcUrl, or null when the URL has no {@code //host} authority. */
    static String jdbcHost(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        int authority = jdbcUrl.indexOf("//");
        if (authority < 0) {
            return null;
        }
        String rest = jdbcUrl.substring(authority + 2);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == ':' || c == ';' || c == '?') {
                end = i;
                break;
            }
        }
        String host = rest.substring(0, end);
        return host.isBlank() ? null : host;
    }
}
