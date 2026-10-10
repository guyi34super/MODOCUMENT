# Connection secrets and egress rules (ws25)

**Owner:** settings & secrets (SE1). **Code:**
`infrastructure/integration/secrets/ConnectorSecretVault`,
`infrastructure/integration/egress/BankEgressGuard`,
`application/usecase/EgressRuleUseCase`.

## Connection secrets

DIRECT-mode connections (REST, database, SFTP, webhook) need credentials:
passwords, tokens, API keys, client secrets, private keys, HMAC secrets.
They are **write-only**. The API and the UI accept a new secret or a
replacement, and only ever answer `secretSet: true|false`. AGENT-mode
connections store no secret at all: the dashboard keeps only a `secretRef`
name, and the value stays in the bank's agent.

### Encryption

| Property | Value |
|---|---|
| Cipher | AES-256-GCM, 96-bit random nonce, 128-bit tag (`infrastructure/security/AesGcm`, shared with the MFA cipher) |
| Master key | `MODOCUMENT_CONNECTOR_KEY` (`modocument.integration.connector-key`): base64 of exactly 32 bytes. Separate from `MODOCUMENT_MFA_KEY` |
| Per-bank key | HKDF-SHA256(master key, salt = 32 zero bytes, info = `modocument-connector:<bankId>`), 32 bytes |
| Associated data | `modocument-connector:<bankId>:v<keyVersion>`, so a ciphertext copied to another bank, or relabelled with another key version, does not open |
| Plaintext | the `SecretMaterial` map as JSON (`{"password":"…"}`) |
| Stored as | `SecretCiphertext` = base64(nonce ‖ ciphertext ‖ tag), `SecretKeyVersion` = the master key's version |

Opening fails with one generic `IllegalStateException` for every cause:
wrong bank, wrong version, tampering or truncation. Neither exceptions nor
logs contain key material or secret values.

### Configuration

```yaml
modocument:
  integration:
    connector-key: ${MODOCUMENT_CONNECTOR_KEY:}                     # active key
    connector-key-version: ${MODOCUMENT_CONNECTOR_KEY_VERSION:1}    # its version
    connector-retired-keys: ${MODOCUMENT_CONNECTOR_RETIRED_KEYS:}   # "1:<base64>,2:<base64>"
```

`MODOCUMENT_CONNECTOR_KEY` and `MODOCUMENT_CONNECTOR_RETIRED_KEYS` also
accept the `_FILE` indirection (Docker/ECS secrets), like the other
secrets.

- **Not set:** the application starts, because a bank without DIRECT
  connections never needs the key. Storing or opening a connection secret
  is refused with a clear `IllegalStateException`. Outside the dev/test
  profiles a WARN is logged at startup. The test profile ships a fixed,
  deliberately worthless key (32 zero bytes).
- **Set but malformed** (not base64, not 32 bytes, a non-positive or
  duplicate version): startup fails in every profile.

Generate a key with `openssl rand -base64 32`. In AWS it lives in Secrets
Manager next to the MFA key (Terraform: SE5).

### Key rotation

New ciphertexts always use the **active** key. Each stored ciphertext
records the version that sealed it, and opening uses that version. To
rotate:

1. Generate a new key: `openssl rand -base64 32`.
2. Deploy with the new key active and the old key retired:
   - `MODOCUMENT_CONNECTOR_KEY=<new>`
   - `MODOCUMENT_CONNECTOR_KEY_VERSION=<old version + 1>`
   - `MODOCUMENT_CONNECTOR_RETIRED_KEYS=<old version>:<old key>` (append
     to any retired keys that are still listed)

   Every task must run this configuration before step 3. A task still on
   the old configuration cannot open secrets sealed under the new version.
3. Re-seal the stored secrets. There is no automatic re-seal job. Each
   connection is re-sealed when its credentials are next replaced through
   the normal connection change (maker/checker
   `INTEGRATION_CONNECTION_CHANGE`). To finish a rotation, have each bank
   re-enter its credentials. Then check that no row still uses the old
   version:
   ```sql
   SELECT BankId, count(*) FROM IntegrationConnection
   WHERE SecretKeyVersion = <old version> GROUP BY BankId;
   ```
4. When that query returns no rows, remove the old key from
   `MODOCUMENT_CONNECTOR_RETIRED_KEYS` and redeploy.

**Suspected key compromise:** rotate as above, and also treat every
credential stored under the old key as compromised. The banks must change
those credentials at their end before re-entering them.

## Egress rules

DIRECT connections leave the platform from the application's network. A
destination that resolves to a **public** address passes under the global
SSRF policy (`OutboundHostPolicy`, `modocument.security.ssrf.*`). A
destination that resolves to a **private, loopback, link-local,
carrier-grade-NAT or unique-local** address (for example a bank network
reached over site-to-site VPN or PrivateLink) passes only when it matches
an **approved egress rule of that bank**:

- the host is the rule's host (exact, case-insensitive), or the address is
  inside the rule's CIDR block (`/8`–`/32` IPv4, `/16`–`/128` IPv6);
- **and** the port is the rule's port.

Every resolved address must pass, so a name that resolves to a public
address and an unapproved private one is refused (DNS rebinding). The
cloud metadata and credential endpoints (`169.254.169.254`,
`169.254.170.2`, `169.254.170.23`, `fd00:ec2::254`, `fd00:ec2::23`), the
wildcard address and multicast are **always** refused, whatever a rule, the
SSRF allow-list or a disabled SSRF policy says. A refusal is a
`SecurityException` that names the host and port, never credentials.
Another bank's rules never apply.

Lifecycle:

| Step | Who | Route | Result |
|---|---|---|---|
| Request | bank `integration:admin` | `POST /api/admin/integrations/egress-rules` `{host \| cidr, port, reason}` | 200, `PENDING`, audit `EGRESS_RULE_REQUESTED`. Nothing opens yet |
| Review | platform `platform:read` | `GET /api/platform/egress-rules[?status=]` | Every bank's pending and approved rules |
| Approve | platform `platform:banks`, then a second platform admin | `POST /api/platform/egress-rules/{id}/approve` → 202 `EGRESS_RULE_APPROVE` | On approval: `APPROVED`, audit `EGRESS_RULE_APPROVED` in the bank and the platform bank (alert rule `platform-change`, HIGH) |
| Revoke | bank `integration:admin` | `DELETE /api/admin/integrations/egress-rules/{id}` (or `POST …/{id}/revoke`) | `REVOKED` (direct), audit `EGRESS_RULE_REVOKED` |

`BankEgressGuard` caches each bank's approved rules for
`modocument.integration.egress-rule-cache-ttl` (30 s). Approving or revoking
a rule clears the cache on the task that handled it. Other tasks see the
change within one TTL.

Connectors call `EgressGuard.checkDestination(bankId, host, port)` right
before connecting. They do not follow redirects on authenticated calls.

Network reachability is a separate layer. A rule is the application-level
permission, but the bank's network still has to be routable from the ECS
tasks. That is the per-bank VPN or PrivateLink onboarding, and the
`bank_egress_cidrs` security-group rules in Terraform.
