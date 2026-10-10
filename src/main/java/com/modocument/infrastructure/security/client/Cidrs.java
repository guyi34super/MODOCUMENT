package com.modocument.infrastructure.security.client;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strict syntax check of an AllowedCidrs entry (ws26): an IPv4 or IPv6
 * literal with an optional prefix length — never a host name (the matcher
 * would resolve it through DNS). Returns the normalized form.
 */
public final class Cidrs {

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-f:]{2,39}$");

    private Cidrs() {
    }

    /**
     * @return the normalized CIDR ({@code 10.0.0.0/8}, {@code 203.0.113.7/32})
     * @throws IllegalArgumentException when it is not an IP literal with a valid prefix
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An allowed CIDR must not be empty.");
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        String address = trimmed;
        Integer prefix = null;
        int slash = trimmed.indexOf('/');
        if (slash >= 0) {
            address = trimmed.substring(0, slash);
            try {
                prefix = Integer.parseInt(trimmed.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw invalid(value);
            }
        }
        boolean v4 = IPV4.matcher(address).matches();
        boolean v6 = !v4 && address.contains(":") && IPV6.matcher(address).matches()
                && address.split("::", -1).length <= 2;
        if (!v4 && !v6) {
            throw invalid(value);
        }
        int max = v4 ? 32 : 128;
        int length = prefix == null ? max : prefix;
        if (length < 0 || length > max) {
            throw invalid(value);
        }
        return address + "/" + length;
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("'" + value + "' is not an IPv4/IPv6 address or CIDR "
                + "(e.g. 203.0.113.0/24).");
    }
}
