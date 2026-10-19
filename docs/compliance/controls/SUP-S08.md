# SUP-S08 — Certificate and cryptographic key lifecycle

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 04 IT Infrastructure | IT Infrastructure & Operations | P2 | Partially Implemented | SUP-S08-E01 | SUP-S08-T01 | R-SUP-S08 |

## Requirement
Nothing expires by surprise and no key is unrecoverable or uncontrolled.
Acceptance: the register shows zero unknown-owner entries and zero entries
with less than 30 days to expiry that are not monitored.

## How MoDocument meets it today
- **Register:** `docs/security/key-certificate-register.md`, updated
  2026-10-19 to match the code. 24 rows: JWT key (K1), MFA key (K2), DB
  credentials (K3, K4), ACM certificates for the ALB and CloudFront (K5,
  K6), RDS CMK (K7), origin-verify value (K8), IdP secrets (K9), LLM key
  (K10), CI OIDC roles (K11), webhook secrets (K12, K13), RDS CA bundle
  (K14), bootstrap admin (K15), connector key (K16), mTLS trust store
  (K17), file-store CMK (K18), integration API client secrets (K19),
  Connector Agent credentials and certificates (K20), agent enrolment
  token (K21), AWS-managed keys (K22), e-mail DKIM keys (K23), GitHub
  OIDC chain (K24). Every row has an owner; values are never recorded.
- **Managed rotation:** KMS CMKs rotate yearly (`enable_key_rotation =
  true`, `infra/terraform/modules/rds/main.tf`,
  `infra/terraform/modules/file-store/main.tf`); ACM renews its
  certificates; the RDS master password is RDS-managed (TF-28).
- **Rotation support in code:** connector key versions with retired keys
  (`src/main/java/com/modocument/infrastructure/integration/secrets/ConnectorSecretVault.java`);
  API client secret rotation with an overlap
  (`src/main/java/com/modocument/api/controller/ApiClientAdminController.java`).
  The Terraform task definition does not yet inject
  `MODOCUMENT_CONNECTOR_KEY_VERSION` / `MODOCUMENT_CONNECTOR_RETIRED_KEYS`
  (DEF-12). JWT rotation has no overlap: all sessions sign in again
  (`docs/security/crypto-standard.md` §3). MFA-key rotation needs a
  re-encryption run that exists only as a procedure.
- **Rotation drill (repository level):** run on 2026-10-19 —
  `ConnectorSecretVaultTest` 13/13 and `IntegrationApiSecurityTest` 15/15
  passed, including the two rotation tests
  ([A8-infra-checks.md](../evidence/A8-infra-checks.md) §2; drill record
  in the register). A live rotation in a deployed environment has not
  been done: `NOT IN REPO (live cloud)`.
- **Expiry alerting:** **none in code.** No EventBridge rule routes ACM
  expiry events; bank CA and agent certificate expiries are not tracked
  (register, "Expiry alerting"). The workbook asks for alerts at 30, 14
  and 7 days.

## Evidence SUP-S08-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Key and certificate register | `docs/security/key-certificate-register.md` | repo |
| Expiry alert configuration | Register "Expiry alerting" section — shows none exists | repo (gap) |
| Rotation drill note | Register "Rotation drill record"; [A8-infra-checks.md](../evidence/A8-infra-checks.md) §2 | run (2026-10-19) |
| Rotation tests | `src/test/java/com/modocument/infrastructure/integration/secrets/ConnectorSecretVaultTest.java`; `src/test/java/com/modocument/api/integration/IntegrationApiSecurityTest.java` | repo + run |
| Crypto standard | `docs/security/crypto-standard.md` | repo |
| Live expiry dates, ACM renewal status | ACM console | `NOT IN REPO (live cloud)` |

## Test SUP-S08-T01
- **Procedure:** (1) compare the register with every secret, key and
  certificate in Terraform and the app configuration; check each row has
  an owner and an expiry or rotation rule; (2) run the rotation tests;
  (3) check for expiry alerting.
- **Expected result:** zero unknown-owner entries and zero unmonitored
  entries expiring within 30 days.
- **Actual result:** (1) seven items were missing (file-store CMK, API
  client secrets, agent credentials, enrolment token, AWS-managed keys,
  DKIM keys, GitHub OIDC chain) and were added as K18–K24; zero unknown owners; (2) 28 tests passed, 0 failed;
  (3) no expiry alerting exists — no production certificates exist yet,
  so nothing is within 30 days of expiry today.
- **Result:** Partial.
- **Tester:** Agent A8 (re-performance); human sign-off required.
- **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| No expiry alerts | Config | EventBridge rule for ACM expiry events → alert topic; reminders at 30/14/7 days for bank CAs and agent certificates | IT Infrastructure — Bhavish Nobeen | Pre-go-live, date to set | Day 60 |
| Connector rotation variables not injected (DEF-12) | Build | Add `MODOCUMENT_CONNECTOR_KEY_VERSION` and the retired keys (as a secret) to the task definition | Bhavish Nobeen | before the first rotation | Day 60 |
| No live rotation drill | Build | Rotate K1, K4 and K16 in staging, time it, record it | Bhavish Nobeen | before go-live | Day 60 |
| MFA-key re-encryption is procedure only | Build | Implement and test the re-encryption run | Software Engineering — Bhavish Nobeen | `TO CONFIRM (organisational)` | Day 90 |
| Register | Doc | Updated to match the code | Agent A8 | 2026-10-19 | Done |

## Risk R-SUP-S08
- **Risk:** a forgotten internal certificate expires; a lost key orphans
  encrypted data.
- **Cause:** no expiry alerting; keys without an escrow copy.
- **Mitigation:** register with owners, ACM auto-renewal, expiry alerts,
  yearly rotation drill, key escrow (PRIV-006). Likelihood, impact and
  residual are set in MO-031.

## To confirm
- `NOT IN REPO (live cloud)`: live certificate expiry dates; ACM renewal status; live rotation drill.
- `TO CONFIRM (organisational)`: reminder tool for bank CA and agent certificate expiry; owner of the MFA-key re-encryption run; DKIM key details (K23).
