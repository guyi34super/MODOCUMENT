# Test-data and environment-segregation policy (SUP-S07)

| Field | Value |
|---|---|
| Control | SUP-S07 Environment segregation and test-data governance (workbook 03) |
| Owner | Software Engineering — Bhavish Nobeen |
| Consulted | The DPO (personal data in any non-production environment) |
| Approver | Reezvi Pydiah (proposed — confirm; docs/compliance/mo-001-governance-structure.md §4, D2) |
| Status | Draft 1.0, 2026-10-19. Not yet approved. |
| Review | Every 12 months, and before the first bank sends data. |

## 1. Environments

| Environment | Where | Data allowed | Defined in |
|---|---|---|---|
| Local development | A developer's machine, Docker Compose | **Synthetic only** | `docker-compose.yml`, `application-local-secrets.yml.example` |
| Automated tests (unit, H2, PostgreSQL ITs) | CI runners and developer machines | **Synthetic only**, created by the tests | `src/test/java/`, `src/main/resources/data.sql` (reference rows for the H2 profile only) |
| End-to-end (Playwright on the compose stack, bank simulator) | CI runner | **Synthetic only**; secrets generated per run and masked | `.github/workflows/ci.yml` (job `e2e`), `e2e/`, `e2e/bank-simulator/` |
| Staging | Its own AWS account (design), af-south-1, VPC 10.41.0.0/16 | **Synthetic only**, plus bank-provided test data only if the bank confirms in writing that it is not real customer data | `infra/terraform/envs/staging/` |
| Production | Its own AWS account, af-south-1, VPC 10.40.0.0/16 | Real bank data | `infra/terraform/envs/production/` |

Today nothing is deployed and no bank data has been received (workbook statement, 2 Oct 2026). Both cloud environments exist only as Terraform that has not been applied (`infra/terraform/README.md`).

## 2. Rules

1. **Production data never leaves production.** No copy, dump, backup restore, export or screenshot of production data goes to staging, CI, a developer machine or a ticket. This includes backups: a production snapshot is restored only into production (or the lock account for a restore drill run by the production team).
2. **No refresh of staging from production.** There is no staging-refresh pipeline and none may be built without a masking step that the DPO approves. Staging is seeded with synthetic data.
3. **Synthetic data rules.** Test identities use reserved domains (`.test`, `example.com`; e.g. `e2e/bank-simulator/sftp/seed/inbound/customers-2024-06-01.csv` uses `@bank-sim.test`). Identifier-shaped values (NIC, account numbers) are invented and must not belong to a real person. Do not use real names of customers or staff.
4. **Secrets are per environment.** Each environment has its own Secrets Manager entries (`infra/terraform/envs/staging/main.tf` and `infra/terraform/envs/production/main.tf`, "Secret shells"), its own state backend and its own CI deploy role. A production credential must never be stored in a lower environment, in CI variables of the `staging` environment, or in the repository. gitleaks scans the full history on every push (`.github/workflows/security.yml`).
5. **No network path from lower environments to production data stores.** Separate accounts and non-overlapping VPC ranges; no VPC peering or transit gateway is defined (`infra/terraform/`). The production database accepts connections only from the production app security group (`infra/terraform/modules/network/main.tf`).
6. **Bank test data.** If a bank wants to test with its own files, it provides test data it confirms in writing is not real customer data. That confirmation is kept with the onboarding record (`TO CONFIRM (organisational)` where).
7. **AI in tests.** Tests and E2E use a fake model (`e2e/stubs/fake-llm.mjs`) or mocks; no test sends data to an external AI provider.

## 3. Exceptions

An exception to rule 1 or 2 needs written approval by the DPO and Reezvi Pydiah, a masking design reviewed against `docs/security/masking-matrix.md`, and a deletion date. None has been granted.

## 4. Checks

| Check | How | Frequency |
|---|---|---|
| Account separation | The provider of each env root is configured with that environment's account. `allowed_account_ids` is not set yet (gap, SUP-S07 page). | Every `terraform plan` |
| Credential replay | A staging credential (JWT, API client, deploy role) is tried against production and must be rejected. Not executable until both accounts exist (`NOT IN REPO (live cloud)`). | Before go-live, then yearly |
| No production data in staging | Review of staging's file store and database contents | Quarterly once staging exists |
| Secret scanning | gitleaks full history | Every push |
