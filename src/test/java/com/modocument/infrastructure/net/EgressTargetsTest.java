package com.modocument.infrastructure.net;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ws25 egress target parsing: host names, IP literals and CIDR blocks, no DNS. */
class EgressTargetsTest {

    @Test
    void hostsAreNormalized() {
        assertThat(EgressTargets.normalizeHost("  DB.Bank.Internal. ")).isEqualTo("db.bank.internal");
        assertThat(EgressTargets.normalizeHost("10.0.0.5")).isEqualTo("10.0.0.5");
        assertThat(EgressTargets.normalizeHost("[FD12:3456::0001]")).isEqualTo("fd12:3456:0:0:0:0:0:1");
    }

    @Test
    void urlsPortsPathsAndJunkAreNotHosts() {
        for (String bad : new String[]{"https://db.example", "db.example:5432", "db.example/x",
                "db example", "-db.example", "user@db.example", ""}) {
            assertThatThrownBy(() -> EgressTargets.normalizeHost(bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ipLiteralsAreRecognizedWithoutDns() {
        assertThat(EgressTargets.isIpLiteral("192.168.1.1")).isTrue();
        assertThat(EgressTargets.isIpLiteral("fd00:ec2::254")).isTrue();
        assertThat(EgressTargets.isIpLiteral("db.example")).isFalse();
        assertThat(EgressTargets.isIpLiteral("999.1.1.1")).isFalse();
        assertThat(EgressTargets.isIpLiteral("not:an:ip")).isFalse();
    }

    @Test
    void cidrBlocksAreCanonicalAndContainTheirAddresses() {
        EgressTargets.Cidr block = EgressTargets.Cidr.parse("10.20.3.4/16");
        assertThat(block.canonical()).isEqualTo("10.20.0.0/16");
        assertThat(block.contains(EgressTargets.literal("10.20.255.255"))).isTrue();
        assertThat(block.contains(EgressTargets.literal("10.21.0.0"))).isFalse();
        assertThat(block.contains(EgressTargets.literal("fd00::1"))).isFalse();

        EgressTargets.Cidr odd = EgressTargets.Cidr.parse("172.16.0.0/12");
        assertThat(odd.contains(EgressTargets.literal("172.31.255.1"))).isTrue();
        assertThat(odd.contains(EgressTargets.literal("172.32.0.1"))).isFalse();

        EgressTargets.Cidr v6 = EgressTargets.Cidr.parse("FD12:3456::/48");
        assertThat(v6.canonical()).isEqualTo("fd12:3456:0:0:0:0:0:0/48");
        assertThat(v6.contains(EgressTargets.literal("fd12:3456:0:ffff::1"))).isTrue();
        assertThat(v6.contains(EgressTargets.literal("fd12:3457::1"))).isFalse();

        assertThat(EgressTargets.Cidr.parse("10.0.0.7/32")
                .contains(EgressTargets.literal("10.0.0.7"))).isTrue();
    }

    @Test
    void tooWideOrMalformedBlocksAreRefused() {
        for (String bad : new String[]{"0.0.0.0/0", "10.0.0.0/7", "::/0", "fd00::/15",
                "10.0.0.0", "10.0.0.0/33", "db.example/24", "10.0.0.0/x", "/24"}) {
            assertThatThrownBy(() -> EgressTargets.Cidr.parse(bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void metadataEndpointsAreKnown() {
        assertThat(EgressTargets.isMetadata(EgressTargets.literal("169.254.169.254"))).isTrue();
        assertThat(EgressTargets.isMetadata(EgressTargets.literal("fd00:ec2::254"))).isTrue();
        assertThat(EgressTargets.isMetadata(EgressTargets.literal("169.254.10.10"))).isFalse();
    }
}
