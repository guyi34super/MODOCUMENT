# A7 runs — commands, dates and results (2026-10-19)

Runs made by agent A7 for controls SEC-025, SEC-027, SEC-028, SEC-029, SEC-034, SEC-035, SEC-042, SUP-S05, SUP-S07 and SUP-S13. All times are UTC. Every run used the working tree at commit `eb53e97` (the pack skeleton on top of `23647fe`, the merge of PR #66). Test data is synthetic only (the suites' own fixtures). The tester is "Agent A7 (automated re-performance)". A human must sign off each result.

Environment: Linux container, JDK 21, Maven, Node 22.22.0 / npm 10.9.4, PostgreSQL 16.14 on 127.0.0.1:55432 for the PostgreSQL runs (production targets PostgreSQL 17, `infra/terraform/modules/rds/main.tf`), semgrep 1.179.0.

## R1 — H2 batch A: change management, audit, logging (16:26:35, 2 min 56 s)

```
mvn -B -Dtest='SettingsMakerCheckerIntegrationTest,MakerCheckerIntegrationTest,SettingsGuardrailTest,MigrationPolicyTest,FlywayVersionRangeTest,VersionScriptsTest,DeployedVersionRecorderTest,SystemVersionServiceTest,AuditLogApiTest,AuditViewAuditQaTest,AuditTrailGapsTest,JpaAuditRecorderTest,RequestTraceabilityE9Test,LogRedactionCanaryTest,StructuredLogRedactionTest,AuditActionCatalogueDocTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: **197 tests, 0 failures, 0 errors, 0 skipped. BUILD SUCCESS.**

| Test class | Tests | Used by |
|---|---|---|
| `SettingsGuardrailTest` | 66 | SEC-025 |
| `MigrationPolicyTest` | 58 | SEC-025 |
| `VersionScriptsTest` | 10 | SEC-025 |
| `AuditLogApiTest` | 9 | SEC-025, SEC-035 |
| `AuditTrailGapsTest` | 9 | SEC-035 |
| `JpaAuditRecorderTest` | 8 | SEC-034, SEC-035 |
| `FlywayVersionRangeTest` | 7 | SEC-025 |
| `MakerCheckerIntegrationTest` | 6 | SEC-025 |
| `SettingsMakerCheckerIntegrationTest` | 5 | SEC-025 |
| `AuditActionCatalogueDocTest` | 4 | SEC-035 |
| `AuditViewAuditQaTest` | 3 | SEC-035 |
| `RequestTraceabilityE9Test` | 3 | SEC-034 |
| `SystemVersionServiceTest` | 3 | SEC-025 |
| `DeployedVersionRecorderTest` | 3 | SEC-025 |
| `StructuredLogRedactionTest` | 2 | SEC-034 |
| `LogRedactionCanaryTest` | 1 | SEC-034 |

## R2 — H2 batch B: cryptography, transport, AI gateway (16:29:50, 2 min 15 s)

```
mvn -B -Dtest='DatabaseTlsConfigTest,MfaSecretCipherTest,ConnectorSecretVaultTest,TotpTest,JwtServiceTest,SecretFileEnvironmentPostProcessorTest,TrustedProxyIntegrationTest,RestApiMtlsTest,HeaderContractTest,AiGatewayC9Test,AiGatewayC8LoggingTest,AiOutputPolicyC10Test,AiOutputScannerTest,NoDirectChatModelAccessGuardTest,AiCircuitBreakerTest,AiGatewayTest,AiPreflightTest,LlmQuotaRetryAfterIntegrationTest,PiiTokenizerTest,IngestionParseStepImplTest,SettingsApiTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: **157 tests, 0 failures, 0 errors, 0 skipped. BUILD SUCCESS.** (`HeaderContractTest` and `SettingsApiTest` matched; every named class ran.)

| Test class | Tests | Used by |
|---|---|---|
| `IngestionParseStepImplTest` | 32 | SUP-S13 |
| `PiiTokenizerTest` | 15 | SUP-S13 |
| `ConnectorSecretVaultTest` | 13 | SEC-028, SEC-029 |
| `SecretFileEnvironmentPostProcessorTest` | 13 | SEC-028, SEC-029 |
| `AiCircuitBreakerTest` | 10 | SUP-S13 |
| `AiOutputScannerTest` | 8 | SUP-S13 |
| `AiPreflightTest` | 8 | SUP-S13 |
| `SettingsApiTest` | 8 | SUP-S13 (bank AI switch) |
| `MfaSecretCipherTest` | 7 | SEC-028, SEC-029 |
| `AiGatewayC9Test` | 6 | SUP-S13 (cross-tenant leakage) |
| `TotpTest` | 6 | SEC-029 |
| `AiGatewayTest` | 5 | SUP-S13 |
| `HeaderContractTest` | 5 | SEC-028 |
| `JwtServiceTest` | 4 | SEC-029 |
| `TrustedProxyIntegrationTest` | 4 | SEC-028 |
| `DatabaseTlsConfigTest` | 3 | SEC-028 |
| `AiOutputPolicyC10Test` | 3 | SUP-S13 |
| `AiGatewayC8LoggingTest` | 3 | SUP-S13 |
| `RestApiMtlsTest` | 2 | SEC-028 |
| `NoDirectChatModelAccessGuardTest` | 1 | SUP-S13 |
| `LlmQuotaRetryAfterIntegrationTest` | 1 | SUP-S13 |

## R3 — PostgreSQL integration tests (16:33:56, 51 s)

```
export MODOCUMENT_IT_JDBC_URL=jdbc:postgresql://127.0.0.1:55432/postgres
export MODOCUMENT_IT_SUPERUSER=postgres
mvn -B -Ppostgres-it -DskipUTs=true -Dit.test='AuditAppendOnlyPgIT,AiResultCacheRlsPgIT,RlsPolicyCoveragePgIT' verify
```

Result: **8 tests, 0 failures. BUILD SUCCESS.** `AuditAppendOnlyPgIT` 5/5 (SEC-035), `AiResultCacheRlsPgIT` 1/1 (SUP-S13), `RlsPolicyCoveragePgIT` 2/2 (SEC-035, SUP-S13). The suite migrates a fresh database with the full Flyway chain (`src/test/java/com/modocument/pgit/PgItSupport.java`).

## R4 — Audit tamper drill on the migrated PostgreSQL schema (16:35, about 1 s)

```
psql -h 127.0.0.1 -p 55432 -U postgres -d <R3 database> -f audit_tamper_demo.sql
```

Output and SQL: [A7-audit-tamper-drill.md](A7-audit-tamper-drill.md). Result: the runtime role holds INSERT and SELECT only; both append-only triggers are enabled; **a superuser who sets `session_replication_role = replica` can update an audit row, and no column or job would detect it** (the update was rolled back). This is the SEC-035 gap.

## R5 — Agent mTLS test (16:36, 13 s)

```
mvn -B -f agent/pom.xml -Dtest='AgentMtlsTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: **5 tests, 0 failures. BUILD SUCCESS.** (SEC-028: agent to MoDocument over HTTPS with a client certificate.)

## R6 — Semgrep, the repository's own rules (16:31)

```
semgrep scan --metrics=off --error --config .semgrep/modocument.yml --exclude target --exclude node_modules
```

Result: **6 rules, 1,033 files, 0 findings, exit 0.** Rules: `modocument-weak-hash-messagedigest`, `modocument-weak-mac`, `modocument-weak-cipher`, `modocument-des-key-spec`, `modocument-raw-chatmodel-outside-ai-gateway`, `modocument-getenv-outside-allowlisted-connector` (`.semgrep/modocument.yml`). The registry packs `p/java` and `p/owasp-top-ten` were not run here (they are fetched from the internet in CI, `.github/workflows/security.yml`).

## R7 — Release SBOMs (CycloneDX), 16:28–16:29

| SBOM | Command | Output (committed) | Components |
|---|---|---|---|
| Backend `modocument` 1.2.0 (includes `modocument-connector-core`) | `mvn -B -Psbom org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeBom` (the plugin and settings of the `sbom` profile in `pom.xml`) | `docs/compliance/evidence/sbom/A7-backend-1.2.0.cdx.json` (CycloneDX 1.6, 554 KB) | 209 |
| Connector Agent `modocument-agent` 1.2.0 | `mvn -B -f agent/pom.xml org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeBom -DoutputFormat=json -DprojectType=application` | `docs/compliance/evidence/sbom/A7-agent-1.2.0.cdx.json` (CycloneDX 1.6, 90 KB) | 34 |
| Frontend `modocument-frontend` 1.2.0, production dependencies | in a scratch copy of `frontend/package.json` + `frontend/package-lock.json`: `npm ci --ignore-scripts`, then `npx --yes @cyclonedx/cyclonedx-npm@4.0.0 --omit dev --output-format JSON` | `docs/compliance/evidence/sbom/A7-frontend-1.2.0.cdx.json` (CycloneDX 1.6, 70 KB) | 35 |

Licence counts and copyleft list: [A7-sbom-summary.md](A7-sbom-summary.md).

**Defect found (R7b).** The CI step `.github/workflows/sbom.yml` (`npm ci --ignore-scripts` then `npm sbom --sbom-format cyclonedx --omit dev`) was re-performed exactly. It produced **32 components and left out `react`, `react-dom` and `scheduler`**, which are runtime dependencies in `frontend/package.json`. The same happens with `npm sbom --package-lock-only --omit dev`. The tool `@cyclonedx/cyclonedx-npm` lists all 35. So the CI frontend SBOM is incomplete (SEC-042 gap).

## R8 — npm audit on the frontend lockfile (16:30)

```
npm audit --audit-level=high      # in the scratch copy of frontend/ after npm ci --ignore-scripts
```

Result: **0 vulnerabilities (info 0, low 0, moderate 0, high 0, critical 0)**, exit 0. A Java CVE scan (Trivy) was **not run**: Trivy is not installed in this container. In CI it runs only in `.github/workflows/release.yml` and `.github/workflows/image-rebuild.yml`, which have not produced a release (R9).

## R9 — GitHub state (read through the GitHub API, 16:20–16:40)

Details: [A7-github-change-trail.md](A7-github-change-trail.md).

- Tags: **none**. Releases: **none**. `release.yml` has never run for a tag, so no GitHub Release, release SBOM or image digest exists.
- PR #66 (head of `main`): all 11 check runs **failed within 1–2 seconds** on 2026-10-18 (jobs were never given a runner). The merge commit message says it "Merged on the local gate while GitHub Actions is not starting jobs (no runner assigned)".
- PR #63 (2026-10-16) is the first PR whose check runs all failed in about 2 seconds. PR #62's checks ran normally (minutes) and passed, except `dependency-review`.
- PRs #32 and #62–#66: **0 reviews** each; merged by the repository owner's account, which also opened them.
- PRs #64 and #65: sampled check runs also failed in 2 seconds (2026-10-17).
- `dependency-review` failed on PRs #32 and #62 while the other checks passed, and the PRs were merged.
- Scheduled runs on 2026-10-19 (`security.yml`, `image-rebuild.yml`) and the Dependabot PR runs (#67–#71) also fail in about 3–4 seconds.

## R10 — Workflow static audit (16:40)

```
python3 -I workflow_audit.py .    # reads .github/workflows/*.yml
```

| Workflow | `uses:` refs | SHA-pinned | Top-level permissions | Jobs with `id-token: write` | Provenance / attestation |
|---|---|---|---|---|---|
| `.github/workflows/ci.yml` | 14 | 14 | contents: read | - | none |
| `.github/workflows/deploy.yml` | 2 | 2 | {} | deploy | none |
| `.github/workflows/image-rebuild.yml` | 5 | 5 | contents: read | - | none |
| `.github/workflows/release.yml` | 22 | 22 | {} | staging, production | `docker buildx --provenance=false` |
| `.github/workflows/sbom.yml` | 6 | 6 | contents: read | - | none |
| `.github/workflows/security.yml` | 7 | 7 | contents: read | - | none |
| `.github/workflows/zap-baseline.yml` | 2 | 2 | contents: read | - | none |

Total: 58 external action references, **58 pinned by full commit SHA**. No workflow creates a build-provenance attestation.

## R11 — Reference check of the security documents (SEC-027 T01), 16:55

```
python3 -I catalogue_check.py . docs/security/security-feature-catalogue.md docs/security/crypto-standard.md docs/security/ai-controls.md docs/security/data-inventory.md
```

The script extracts every backticked path (expanding `…/` to `src/main/java/com/modocument/`) and every backticked test-class name, and checks that each exists in `src/`, `agent/` or `connector-core/`. Result: **0 missing.** Catalogue: 106 path references and 100 test-class references (95 distinct classes); crypto standard: 57 paths, 12 test classes; AI controls: 16 paths, 25 test classes; data inventory: 1 path, 4 test classes. The same check over A7's ten control pages, three policies and four evidence files: 0 missing. Of the 95 test classes named in the catalogue, 34 were re-run in R1–R3 and R5 (315 tests, 0 failures).

## R12 — Pack checker (16:57)

```
python3 scripts/compliance/check_pack.py
```

Result in this worktree: `controls 10/57 answers 7/44 evidence 10/57 tests 10/57`; every remaining problem is a page, data file or task document owned by another agent. **No problem reported for A7's controls or paths.**
