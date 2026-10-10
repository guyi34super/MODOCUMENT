package com.modocument.infrastructure.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parsing and matching of egress destinations (ws25): host names, IP
 * literals and CIDR blocks as {@code BankEgressRule} stores them, and the
 * cloud metadata endpoints no rule can ever open. Never touches DNS: IP
 * literals are parsed, names are only shape-checked.
 */
public final class EgressTargets {

    // Declared before METADATA_ADDRESSES: its initializer parses with them.
    private static final Pattern HOST_NAME = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    /**
     * Instance-metadata and credential endpoints (AWS IMDS v4/v6, the ECS
     * task-credential and EKS pod-identity agents, the IPv6 IMDS DNS) —
     * refused for every bank, whatever its rules say.
     */
    public static final List<InetAddress> METADATA_ADDRESSES = List.of(
            literal("169.254.169.254"),
            literal("169.254.170.2"),
            literal("169.254.170.23"),
            literal("fd00:ec2::254"),
            literal("fd00:ec2::23"));

    private EgressTargets() {
    }

    /**
     * Canonical form of a host as rules store and compare it: trimmed,
     * lower-case, without IPv6 brackets or a trailing root dot; IP literals
     * in their canonical textual form.
     *
     * @throws IllegalArgumentException not a host name or IP literal (a URL,
     *     a port, a path, whitespace...)
     */
    public static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("A host is required.");
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.endsWith(".") && !value.contains(":")) {
            value = value.substring(0, value.length() - 1);
        }
        if (isIpLiteral(value)) {
            return literal(value).getHostAddress();
        }
        if (!HOST_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("'" + abbreviate(host) + "' is not a host name or "
                    + "IP address (no scheme, port or path).");
        }
        return value;
    }

    /** Whether the text is an IPv4 or IPv6 literal (no DNS involved). */
    public static boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (IPV4.matcher(value).matches()) {
            return true;
        }
        if (value.indexOf(':') < 0) {
            return false;
        }
        try {
            // A string with ':' is parsed as an IPv6 literal, never looked up.
            InetAddress.getByName(value.startsWith("[") ? value : "[" + value + "]");
            return true;
        } catch (UnknownHostException | SecurityException e) {
            return false;
        }
    }

    /** Parses an IP literal (no DNS). */
    public static InetAddress literal(String value) {
        String text = value.trim();
        if (!IPV4.matcher(text).matches() && text.indexOf(':') < 0) {
            throw new IllegalArgumentException("Not an IP address.");
        }
        try {
            return InetAddress.getByName(text.indexOf(':') >= 0 && !text.startsWith("[")
                    ? "[" + text + "]" : text);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Not an IP address.");
        }
    }

    /** Whether the address is a cloud metadata / credential endpoint. */
    public static boolean isMetadata(InetAddress address) {
        return METADATA_ADDRESSES.contains(address);
    }

    /** A CIDR block: network address + prefix length. */
    public record Cidr(InetAddress network, int prefix) {

        /**
         * Parses {@code a.b.c.d/n} (n 8–32) or {@code ipv6/n} (n 16–128);
         * host bits are cleared, so {@code 10.1.2.3/16} becomes
         * {@code 10.1.0.0/16}. Wider blocks are refused: a rule names a
         * bank's network, not the internet.
         *
         * @throws IllegalArgumentException malformed or too wide
         */
        public static Cidr parse(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("A CIDR block is required.");
            }
            String value = text.trim().toLowerCase(Locale.ROOT);
            int slash = value.indexOf('/');
            if (slash <= 0 || slash == value.length() - 1) {
                throw new IllegalArgumentException("'" + abbreviate(text) + "' is not a CIDR block "
                        + "(e.g. 10.20.0.0/16).");
            }
            InetAddress address;
            int prefix;
            try {
                address = literal(value.substring(0, slash));
                prefix = Integer.parseInt(value.substring(slash + 1));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + abbreviate(text) + "' is not a CIDR block "
                        + "(e.g. 10.20.0.0/16).");
            }
            boolean v4 = address instanceof Inet4Address;
            int min = v4 ? 8 : 16;
            int max = v4 ? 32 : 128;
            if (prefix < min || prefix > max) {
                throw new IllegalArgumentException("The CIDR prefix must be between /" + min
                        + " and /" + max + " for " + (v4 ? "IPv4" : "IPv6") + ".");
            }
            return new Cidr(mask(address, prefix), prefix);
        }

        /** Whether the address lies in this block (same family only). */
        public boolean contains(InetAddress address) {
            if (address == null
                    || (address instanceof Inet6Address) != (network instanceof Inet6Address)) {
                return false;
            }
            return mask(address, prefix).equals(network);
        }

        /** Canonical text, e.g. {@code 10.20.0.0/16}. */
        public String canonical() {
            return network.getHostAddress() + "/" + prefix;
        }

        private static InetAddress mask(InetAddress address, int prefix) {
            byte[] bytes = address.getAddress();
            for (int i = 0; i < bytes.length; i++) {
                int bitsHere = Math.max(0, Math.min(8, prefix - i * 8));
                bytes[i] = (byte) (bytes[i] & (0xff << (8 - bitsHere)));
            }
            try {
                return InetAddress.getByAddress(bytes);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static String abbreviate(String text) {
        String value = text.strip();
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }
}
