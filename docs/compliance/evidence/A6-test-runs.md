# A6 evidence — test and scan runs (2026-10-19)

Runs made by Agent A6 for controls PRIV-004, PRIV-013, SEC-006, SEC-007, SEC-010, SEC-014,
SEC-018, SEC-019, SEC-020, SEC-021, SEC-022, SUP-S01 and SUP-S02. Synthetic data only; no secret,
token or real host name is reproduced here.

| | |
|---|---|
| Code under test | application code of `origin/main` at `23647fe` (PR #66); the worktree adds only `docs/compliance/` and `scripts/compliance/` |
| Environment | OpenJDK 21.0.11, Maven (`/opt/maven`), Spring `test` profile on H2; `modocument-connector-core` 1.2.0 from the local Maven repository; no PostgreSQL (`-Ppostgres-it` suites not run) |
| Who | Agent A6 (automated re-performance); human sign-off required |

## Totals

| Run | Started (UTC) | Ended (UTC) | Classes | Tests | Failures + errors | Skipped |
|---|---|---|---|---|---|---|
| Batch 1 — authentication, MFA, passwords, secrets, log redaction | 2026-10-19 16:24:35 | 16:27:47 | 31 | 254 | 0 | 0 |
| Batch 2 — API contract, integration API, rate limits, headers, uploads | 2026-10-19 16:28:09 | 16:33:04 | 23 | 168 | 0 | 0 |
| Batch 3 — privacy, masking, DSR, retention, object-level access | 2026-10-19 16:33:15 | 16:34:49 | 12 | 76 | 0 | 0 |
| **All** | | | **66** | **498** | **0** | **0** |

Counts are read from the Surefire XML reports (`target/surefire-reports/TEST-*.xml`) of each run.

## Commands

```bash
# Batch 1
mvn -q -B -Dsurefire.failIfNoSpecifiedTests=false test -Dtest='AuthUseCaseTest,AuthFlowIntegrationTest,AuthControllerTest,JwtServiceTest,RevocationValidatorTest,MfaUseCaseTest,MfaFlowIntegrationTest,MfaRateLimiterTest,MfaSecretCipherTest,TotpTest,Base32Test,PasswordPolicyTest,LoginLockoutPolicyTest,AccountLockoutIntegrationTest,UserSecurityServiceTest,KeycloakRealmConfigTest,OidcLoginServiceTest,OidcLoginSuccessHandlerTest,OidcRegistrationFactoryTest,AuthProvidersConfiguredTest,AuthProvidersControllerTest,LogRedactionCanaryTest,RedactingConverterTest,StructuredLogRedactionTest,SecretFileEnvironmentPostProcessorTest,ConnectorSecretVaultTest,BootstrapAdminRunnerTest,UserAdminUseCaseTest,UserAdminIntegrationTest,AgentEnrolmentTokenTest,ClientCredentialPrimitivesTest'

# Batch 2
mvn -q -B -Dsurefire.failIfNoSpecifiedTests=false test -Dtest='OpenApiContractTest,ApiDocsExposureTest,DeprecatedEndpointsTest,IntegrationApiSecurityTest,IntegrationApiTest,IntegrationAgentApiTest,AgentProtocolSecurityChainTest,AgentProtocolControllerTest,ApiClientProvisioningTest,ApiRateLimitIntegrationTest,WebhookIntegrationConnectorTest,ConnectorSecurityQaTest,IntegrationHubSecurityQaTest,ApiHygieneQaTest,RestApiMtlsTest,HeaderContractTest,TrustedProxyIntegrationTest,UploadContentCheckerTest,UploadContentGateSec030Test,UploadOwnershipEnforcementTest,FixedWindowRateLimiterTest,RoutePermissionCoverageTest,LlmQuotaRetryAfterIntegrationTest'

# Batch 3
mvn -q -B -Dsurefire.failIfNoSpecifiedTests=false test -Dtest='DataSubjectRightsGateC12Test,RetentionPurgeGateC5Test,MaskingGateC2Test,MaskingMatrixDocTest,MaskerTest,PiiClassifierTest,PiiMaskingServiceTest,PiiTokenizerTest,FlowRecordsApiTest,AiGatewayC9Test,CrossUserAccessTest,UserAdminActionsIntegrationTest'
```

## Batch 1 — per class

| Test class | Tests | Failures + errors | Skipped |
|---|---|---|---|
| `AccountLockoutIntegrationTest` | 2 | 0 | 0 |
| `AgentEnrolmentTokenTest` | 4 | 0 | 0 |
| `AuthControllerTest` | 7 | 0 | 0 |
| `AuthFlowIntegrationTest` | 10 | 0 | 0 |
| `AuthProvidersConfiguredTest` | 3 | 0 | 0 |
| `AuthProvidersControllerTest` | 2 | 0 | 0 |
| `AuthUseCaseTest` | 37 | 0 | 0 |
| `Base32Test` | 5 | 0 | 0 |
| `BootstrapAdminRunnerTest` | 13 | 0 | 0 |
| `ClientCredentialPrimitivesTest` | 6 | 0 | 0 |
| `ConnectorSecretVaultTest` | 13 | 0 | 0 |
| `JwtServiceTest` | 4 | 0 | 0 |
| `KeycloakRealmConfigTest` | 6 | 0 | 0 |
| `LogRedactionCanaryTest` | 1 | 0 | 0 |
| `LoginLockoutPolicyTest` | 4 | 0 | 0 |
| `MfaFlowIntegrationTest` | 6 | 0 | 0 |
| `MfaRateLimiterTest` | 4 | 0 | 0 |
| `MfaSecretCipherTest` | 7 | 0 | 0 |
| `MfaUseCaseTest` | 10 | 0 | 0 |
| `OidcLoginServiceTest` | 18 | 0 | 0 |
| `OidcLoginSuccessHandlerTest` | 2 | 0 | 0 |
| `OidcRegistrationFactoryTest` | 5 | 0 | 0 |
| `PasswordPolicyTest` | 8 | 0 | 0 |
| `RedactingConverterTest` | 15 | 0 | 0 |
| `RevocationValidatorTest` | 7 | 0 | 0 |
| `SecretFileEnvironmentPostProcessorTest` | 13 | 0 | 0 |
| `StructuredLogRedactionTest` | 2 | 0 | 0 |
| `TotpTest` | 6 | 0 | 0 |
| `UserAdminIntegrationTest` | 10 | 0 | 0 |
| `UserAdminUseCaseTest` | 22 | 0 | 0 |
| `UserSecurityServiceTest` | 2 | 0 | 0 |
| **Total (31 classes)** | **254** | **0** | **0** |

## Batch 2 — per class

| Test class | Tests | Failures + errors | Skipped |
|---|---|---|---|
| `AgentProtocolControllerTest` | 10 | 0 | 0 |
| `AgentProtocolSecurityChainTest` | 4 | 0 | 0 |
| `ApiClientProvisioningTest` | 5 | 0 | 0 |
| `ApiDocsExposureTest` | 3 | 0 | 0 |
| `ApiHygieneQaTest` | 22 | 0 | 0 |
| `ApiRateLimitIntegrationTest` | 16 | 0 | 0 |
| `ConnectorSecurityQaTest` | 3 | 0 | 0 |
| `DeprecatedEndpointsTest` | 3 | 0 | 0 |
| `FixedWindowRateLimiterTest` | 5 | 0 | 0 |
| `HeaderContractTest` | 5 | 0 | 0 |
| `IntegrationAgentApiTest` | 6 | 0 | 0 |
| `IntegrationApiSecurityTest` | 15 | 0 | 0 |
| `IntegrationApiTest` | 7 | 0 | 0 |
| `IntegrationHubSecurityQaTest` | 24 | 0 | 0 |
| `LlmQuotaRetryAfterIntegrationTest` | 1 | 0 | 0 |
| `OpenApiContractTest` | 7 | 0 | 0 |
| `RestApiMtlsTest` | 2 | 0 | 0 |
| `RoutePermissionCoverageTest` | 2 | 0 | 0 |
| `TrustedProxyIntegrationTest` | 4 | 0 | 0 |
| `UploadContentCheckerTest` | 8 | 0 | 0 |
| `UploadContentGateSec030Test` | 7 | 0 | 0 |
| `UploadOwnershipEnforcementTest` | 4 | 0 | 0 |
| `WebhookIntegrationConnectorTest` | 5 | 0 | 0 |
| **Total (23 classes)** | **168** | **0** | **0** |

## Batch 3 — per class

| Test class | Tests | Failures + errors | Skipped |
|---|---|---|---|
| `AiGatewayC9Test` | 6 | 0 | 0 |
| `CrossUserAccessTest` | 5 | 0 | 0 |
| `DataSubjectRightsGateC12Test` | 3 | 0 | 0 |
| `FlowRecordsApiTest` | 11 | 0 | 0 |
| `MaskerTest` | 8 | 0 | 0 |
| `MaskingGateC2Test` | 4 | 0 | 0 |
| `MaskingMatrixDocTest` | 2 | 0 | 0 |
| `PiiClassifierTest` | 6 | 0 | 0 |
| `PiiMaskingServiceTest` | 5 | 0 | 0 |
| `PiiTokenizerTest` | 15 | 0 | 0 |
| `RetentionPurgeGateC5Test` | 2 | 0 | 0 |
| `UserAdminActionsIntegrationTest` | 9 | 0 | 0 |
| **Total (12 classes)** | **76** | **0** | **0** |

## Other runs

| Run | Command | Date (UTC) | Result |
|---|---|---|---|
| nginx header check (SUP-S01) | `scripts/check-nginx-headers.sh` and `scripts/check-nginx-headers.sh frontend/nginx.mtls-dev.conf` | 2026-10-19 16:28 | OK — 7 locations in `frontend/nginx.conf`, 3 in `frontend/nginx.mtls-dev.conf`; exit 0 |
| Secret scan of the full history (SEC-018, SEC-010) | `gitleaks detect --source . --config .gitleaks.toml --gitleaks-ignore-path .gitleaksignore --log-opts=origin/main --redact` (gitleaks 8.21.2) | 2026-10-19 16:31 | 270 commits scanned, no leaks found, with the `.gitleaks.toml` allowlist and the 27 fingerprints pinned in `.gitleaksignore` (the branch-protection document still says six; the pinned entries were not re-reviewed in this run) |
| Common-password list size (SEC-021) | count of non-comment, non-blank lines in `src/main/resources/security/common-passwords.txt` | 2026-10-19 | 11,384 entries; `password123` present |
| Git history analysis (SEC-010) | commands in `docs/compliance/policies/code-review-report-2026-Q4.md` §1 | 2026-10-19 | 32 GitHub merges, 6 direct pushes, PRs #63–#66 merged on a local gate |

## Not run

- PostgreSQL integration suites (`-Ppostgres-it`, e.g. `ApiClientAuthPgIT`, `MfaLoginPgIT`) — no
  database in this environment.
- ZAP baseline/API scan and the external header-grade scan — no deployed stack and GitHub Actions
  is not starting jobs.
- Semgrep — not re-run for this pack; the last runs are recorded in the merge messages of PRs
  #63–#66.
