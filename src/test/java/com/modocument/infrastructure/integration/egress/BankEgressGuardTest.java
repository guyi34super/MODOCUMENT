package com.modocument.infrastructure.integration.egress;

import com.modocument.domain.entity.BankEgressRule;
import com.modocument.domain.model.integration.EgressRuleStatus;
import com.modocument.infrastructure.net.EgressTargets;
import com.modocument.infrastructure.net.OutboundHostPolicy;
import com.modocument.infrastructure.persistence.BankEgressRuleJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ws25 bank-aware egress guard: public destinations pass under the global
 * SSRF policy, private ones only with an APPROVED rule of the same bank
 * (host or CIDR, and port), metadata endpoints never. DNS is stubbed.
 */
class BankEgressGuardTest {

    private static final int BANK_A = 10;
    private static final int BANK_B = 20;

    private final Map<String, InetAddress[]> dns = new HashMap<>();
    private final Map<Integer, List<BankEgressRule>> approved = new HashMap<>();
    private BankEgressRuleJpaRepository repository;
    private BankEgressGuard guard;

    @BeforeEach
    void setUp() {
        repository = mock(BankEgressRuleJpaRepository.class);
        when(repository.findByBankIdAndStatus(anyInt(), eq(EgressRuleStatus.APPROVED)))
                .thenAnswer(inv -> approved.getOrDefault(inv.<Integer>getArgument(0), List.of()));
        guard = guard(OutboundHostPolicy.enforcing(), Duration.ZERO);
        resolve("api.public.example", "93.184.216.34");
        resolve("db.bank-a.internal", "10.20.1.5");
        resolve("mixed.example", "93.184.216.34", "10.20.1.5");
        resolve("metadata.example", "169.254.169.254");
        resolve("ecs-creds.example", "169.254.170.2");
        resolve("v6-metadata.example", "fd00:ec2::254");
        resolve("loopback.example", "127.0.0.1");
    }

    private BankEgressGuard guard(OutboundHostPolicy policy, Duration ttl) {
        return new BankEgressGuard(policy, repository, null, ttl, this::lookup,
                Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC));
    }

    private InetAddress[] lookup(String host) throws UnknownHostException {
        InetAddress[] found = dns.get(host);
        if (found != null) {
            return found;
        }
        if (EgressTargets.isIpLiteral(host)) {
            return new InetAddress[]{EgressTargets.literal(host)};
        }
        throw new UnknownHostException(host);
    }

    private void resolve(String host, String... addresses) {
        InetAddress[] resolved = new InetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            resolved[i] = EgressTargets.literal(addresses[i]);
        }
        dns.put(host, resolved);
    }

    private void approve(int bankId, String host, String cidr, int port) {
        BankEgressRule rule = new BankEgressRule();
        rule.setBankId(bankId);
        rule.setHost(host);
        rule.setCidr(cidr);
        rule.setPort(port);
        rule.setStatus(EgressRuleStatus.APPROVED);
        approved.computeIfAbsent(bankId, ignored -> new ArrayList<>()).add(rule);
    }

    @Test
    void aPublicDestinationPasses() {
        assertThatCode(() -> guard.checkDestination(BANK_A, "api.public.example", 443))
                .doesNotThrowAnyException();
        // No rule lookup is needed for public addresses.
        verify(repository, times(0)).findByBankIdAndStatus(anyInt(), eq(EgressRuleStatus.APPROVED));
    }

    @Test
    void aPrivateDestinationWithoutARuleIsRefusedNamingTheHost() {
        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "db.bank-a.internal", 5432))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("db.bank-a.internal:5432")
                .hasMessageContaining("no approved egress rule");
    }

    @Test
    void aPrivateDestinationWithAnApprovedHostRulePasses() {
        approve(BANK_A, "db.bank-a.internal", null, 5432);

        assertThatCode(() -> guard.checkDestination(BANK_A, "DB.Bank-A.Internal.", 5432))
                .doesNotThrowAnyException();
    }

    @Test
    void theWrongPortIsRefused() {
        approve(BANK_A, "db.bank-a.internal", null, 5432);

        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "db.bank-a.internal", 1433))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining(":1433");
    }

    @Test
    void aCidrRuleCoversTheAddressesInsideItOnly() {
        approve(BANK_A, null, "10.20.0.0/16", 5432);

        assertThatCode(() -> guard.checkDestination(BANK_A, "db.bank-a.internal", 5432))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.checkDestination(BANK_A, "10.20.255.1", 5432))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "10.21.0.1", 5432))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void anIpLiteralHostRuleMatchesTheResolvedAddress() {
        approve(BANK_A, "10.20.1.5", null, 22);

        assertThatCode(() -> guard.checkDestination(BANK_A, "db.bank-a.internal", 22))
                .doesNotThrowAnyException();
    }

    @Test
    void metadataEndpointsAreAlwaysRefused() {
        approve(BANK_A, null, "169.254.0.0/16", 80);
        approve(BANK_A, "metadata.example", null, 80);
        approve(BANK_A, null, "fd00:ec2::/32", 80);

        for (String host : List.of("metadata.example", "169.254.169.254", "ecs-creds.example",
                "v6-metadata.example", "[fd00:ec2::254]")) {
            assertThatThrownBy(() -> guard.checkDestination(BANK_A, host, 80))
                    .as(host)
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("metadata");
        }
        // Even with the SSRF policy switched off or the host allow-listed.
        BankEgressGuard lax = guard(new OutboundHostPolicy(false, new String[]{"metadata.example"}),
                Duration.ZERO);
        assertThatThrownBy(() -> lax.checkDestination(BANK_A, "metadata.example", 80))
                .isInstanceOf(SecurityException.class);
        // Other link-local addresses in an approved block still pass.
        assertThatCode(() -> guard.checkDestination(BANK_A, "169.254.10.10", 80))
                .doesNotThrowAnyException();
    }

    @Test
    void anotherBanksRuleDoesNotApply() {
        approve(BANK_B, "db.bank-a.internal", null, 5432);
        approve(BANK_B, null, "10.0.0.0/8", 5432);

        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "db.bank-a.internal", 5432))
                .isInstanceOf(SecurityException.class);
        assertThatCode(() -> guard.checkDestination(BANK_B, "db.bank-a.internal", 5432))
                .doesNotThrowAnyException();
    }

    @Test
    void aNameResolvingToAPublicAndAnUnapprovedPrivateAddressIsRefused() {
        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "mixed.example", 443))
                .isInstanceOf(SecurityException.class);
        approve(BANK_A, null, "10.20.1.0/24", 443);
        assertThatCode(() -> guard.checkDestination(BANK_A, "mixed.example", 443))
                .doesNotThrowAnyException();
    }

    @Test
    void loopbackNeedsARuleToo() {
        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "loopback.example", 8080))
                .isInstanceOf(SecurityException.class);
        approve(BANK_A, "loopback.example", null, 8080);
        assertThatCode(() -> guard.checkDestination(BANK_A, "loopback.example", 8080))
                .doesNotThrowAnyException();
    }

    @Test
    void unresolvableMalformedAndWildcardDestinationsAreRefused() {
        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "nowhere.invalid", 443))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("cannot be resolved");
        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "https://x.example/path", 443))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "0.0.0.0", 443))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "api.public.example", 0))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> guard.checkDestination(BANK_A, null, 443))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void refusalsNeverEchoUserinfo() {
        assertThatThrownBy(() -> guard.checkDestination(BANK_A, "svc:hunter2hunter2@db.example", 5432))
                .isInstanceOf(SecurityException.class)
                .hasMessageNotContaining("hunter2");
    }

    @Test
    void theGlobalAllowListAndADisabledPolicyStillApply() {
        BankEgressGuard allowListed = guard(
                new OutboundHostPolicy(true, new String[]{"db.bank-a.internal"}), Duration.ZERO);
        assertThatCode(() -> allowListed.checkDestination(BANK_A, "db.bank-a.internal", 5432))
                .doesNotThrowAnyException();
        BankEgressGuard off = guard(OutboundHostPolicy.disabled(), Duration.ZERO);
        assertThatCode(() -> off.checkDestination(BANK_A, "db.bank-a.internal", 5432))
                .doesNotThrowAnyException();
    }

    @Test
    void approvedRulesAreCachedPerBankUntilInvalidated() {
        BankEgressGuard cached = guard(OutboundHostPolicy.enforcing(), Duration.ofSeconds(30));
        assertThatThrownBy(() -> cached.checkDestination(BANK_A, "db.bank-a.internal", 5432))
                .isInstanceOf(SecurityException.class);
        approve(BANK_A, "db.bank-a.internal", null, 5432);
        // Still the cached (empty) rule set...
        assertThatThrownBy(() -> cached.checkDestination(BANK_A, "db.bank-a.internal", 5432))
                .isInstanceOf(SecurityException.class);
        // ...until the approval invalidates the bank's entry.
        cached.invalidate(BANK_A);
        assertThatCode(() -> cached.checkDestination(BANK_A, "db.bank-a.internal", 5432))
                .doesNotThrowAnyException();
        verify(repository, times(2)).findByBankIdAndStatus(BANK_A, EgressRuleStatus.APPROVED);
        assertThat(cached.approvedRules(BANK_A)).hasSize(1);
        verify(repository, times(2)).findByBankIdAndStatus(BANK_A, EgressRuleStatus.APPROVED);
    }
}
