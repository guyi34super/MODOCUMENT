package com.modocument.infrastructure.integration.egress;

import com.modocument.application.port.TenantContext;
import com.modocument.application.port.integration.EgressGuard;
import com.modocument.domain.entity.BankEgressRule;
import com.modocument.domain.model.integration.EgressRuleStatus;
import com.modocument.infrastructure.net.EgressTargets;
import com.modocument.infrastructure.net.OutboundHostPolicy;
import com.modocument.infrastructure.persistence.BankEgressRuleJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bank-aware egress check of DIRECT-mode connections (ws25,
 * {@link EgressGuard}). For {@code (bankId, host, port)}:
 * <ol>
 *   <li>the host is resolved (an unresolvable host is refused);</li>
 *   <li>a resolved cloud metadata / credential endpoint
 *       ({@link EgressTargets#METADATA_ADDRESSES}) is refused ALWAYS — no
 *       rule, allow-list or disabled SSRF policy opens it;</li>
 *   <li>a host on the global SSRF allow-list passes (operator decision);</li>
 *   <li>every resolved address must then be either PUBLIC under the global
 *       {@link OutboundHostPolicy} (or that policy is switched off), or match
 *       an APPROVED {@code BankEgressRule} of THIS bank: exact host match or
 *       CIDR containing the address, and the same port. A name resolving to
 *       a public and an unapproved private address is refused (DNS
 *       rebinding).</li>
 * </ol>
 * A refusal is a {@link SecurityException} naming the host and port — never
 * credentials. Another bank's rules never apply.
 *
 * <p>Each bank's approved rules are cached for
 * {@code modocument.integration.egress-rule-cache-ttl} (30 s); approving or
 * revoking a rule invalidates that bank's entry at once on this task (other
 * tasks pick it up within the TTL — a revocation therefore takes at most
 * one TTL to reach every task).
 *
 * <p><b>Tenancy.</b> The rules are read under
 * {@code TenantContext.runAs(bankId)} in a read-only transaction of their
 * own, so the check works the same from a request thread, the flow
 * scheduler or a SYSTEM context — a registered TenantContext call site.
 */
@Component
public class BankEgressGuard implements EgressGuard {

    /** Resolves a host name to its addresses; replaceable in unit tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private record CachedRules(List<BankEgressRule> rules, Instant loadedAt) {
    }

    private final OutboundHostPolicy policy;
    private final BankEgressRuleJpaRepository rules;
    private final TransactionTemplate readTx;
    private final Duration cacheTtl;
    private final Resolver resolver;
    private final Clock clock;
    private final Map<Integer, CachedRules> cache = new ConcurrentHashMap<>();

    @Autowired
    public BankEgressGuard(OutboundHostPolicy policy, BankEgressRuleJpaRepository rules,
                           PlatformTransactionManager transactionManager,
                           @Value("${modocument.integration.egress-rule-cache-ttl:30s}")
                           Duration cacheTtl) {
        this(policy, rules, transactionManager, cacheTtl, InetAddress::getAllByName,
                Clock.systemUTC());
    }

    /** Full constructor for unit tests (resolver and clock injected; transactions optional). */
    public BankEgressGuard(OutboundHostPolicy policy, BankEgressRuleJpaRepository rules,
                           PlatformTransactionManager transactionManager, Duration cacheTtl,
                           Resolver resolver, Clock clock) {
        this.policy = policy;
        this.rules = rules;
        if (transactionManager != null) {
            this.readTx = new TransactionTemplate(transactionManager);
            this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.readTx.setReadOnly(true);
        } else {
            this.readTx = null;
        }
        this.cacheTtl = cacheTtl == null ? Duration.ZERO : cacheTtl;
        this.resolver = resolver;
        this.clock = clock;
    }

    @Override
    public void checkDestination(int bankId, String host, int port) {
        // Never echo userinfo (user:password@host) should a caller pass an authority.
        String shown = host == null ? "(none)" : host.strip();
        int at = shown.lastIndexOf('@');
        if (at >= 0) {
            shown = shown.substring(at + 1);
        }
        if (port < 1 || port > 65535) {
            throw refused(shown, port, "the port is out of range");
        }
        String normalized;
        try {
            normalized = EgressTargets.normalizeHost(host);
        } catch (IllegalArgumentException e) {
            throw refused(shown, port, "it is not a host name or IP address");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(normalized);
        } catch (UnknownHostException | SecurityException e) {
            throw refused(shown, port, "it cannot be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw refused(shown, port, "it cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (EgressTargets.isMetadata(address)) {
                throw refused(shown, port, "it is a cloud metadata endpoint");
            }
            if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
                throw refused(shown, port, "it is not a unicast destination");
            }
        }
        if (policy.isAllowListed(normalized)) {
            return;
        }
        List<BankEgressRule> approved = null;
        for (InetAddress address : addresses) {
            if (!policy.isEnabled() || !OutboundHostPolicy.isInternal(address)) {
                continue;
            }
            if (approved == null) {
                approved = approvedRules(bankId);
            }
            if (!matchesAny(approved, normalized, address, port)) {
                throw refused(shown, port, "it resolves to a private address with no approved "
                        + "egress rule for this bank and port");
            }
        }
    }

    /** Drops one bank's cached rules (after an approval or a revocation commits). */
    public void invalidate(int bankId) {
        cache.remove(bankId);
    }

    /** The bank's APPROVED rules, from the cache while fresh. */
    List<BankEgressRule> approvedRules(int bankId) {
        Instant now = clock.instant();
        CachedRules cached = cache.get(bankId);
        if (cached != null && !cacheTtl.isZero()
                && cached.loadedAt().plus(cacheTtl).isAfter(now)) {
            return cached.rules();
        }
        List<BankEgressRule> loaded = load(bankId);
        if (!cacheTtl.isZero()) {
            cache.put(bankId, new CachedRules(loaded, now));
        }
        return loaded;
    }

    private List<BankEgressRule> load(int bankId) {
        try {
            return TenantContext.runAs(bankId, () -> {
                List<BankEgressRule> found = readTx == null
                        ? rules.findByBankIdAndStatus(bankId, EgressRuleStatus.APPROVED)
                        : readTx.execute(status ->
                        rules.findByBankIdAndStatus(bankId, EgressRuleStatus.APPROVED));
                return List.copyOf(found == null ? new ArrayList<>() : found);
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Egress rules could not be read.", e);
        }
    }

    static boolean matchesAny(List<BankEgressRule> approved, String normalizedHost,
                              InetAddress address, int port) {
        for (BankEgressRule rule : approved) {
            if (rule.getPort() != port || rule.getStatus() != EgressRuleStatus.APPROVED) {
                continue;
            }
            if (rule.getHost() != null && hostMatches(rule.getHost(), normalizedHost, address)) {
                return true;
            }
            if (rule.getCidr() != null && cidrContains(rule.getCidr(), address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hostMatches(String ruleHost, String normalizedHost,
                                       InetAddress address) {
        if (ruleHost.equalsIgnoreCase(normalizedHost)) {
            return true;
        }
        if (EgressTargets.isIpLiteral(ruleHost)) {
            try {
                return EgressTargets.literal(ruleHost).equals(address);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean cidrContains(String cidr, InetAddress address) {
        try {
            return EgressTargets.Cidr.parse(cidr).contains(address);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static SecurityException refused(String host, int port, String why) {
        String shown = host.length() > 120 ? host.substring(0, 120) + "…" : host;
        return new SecurityException("The destination " + shown + ":" + port
                + " is not an allowed egress target: " + why + ".");
    }
}
