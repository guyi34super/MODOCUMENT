# PRIV-004 — Built on privacy by design

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 03 Software Engineering | Software Engineering | P2 | Partially Implemented | PRIV-004-E01 | PRIV-004-T01 | R-PRIV-004 |

## Requirement
Show that data protection is engineered into the development life cycle, not bolted on. Gate C2/C9
acceptance: a search of API responses shows no untagged personal data.

## How MoDocument meets it today
- **PR privacy checklist:** every pull request carries "Privacy checklist (PRIV-004)" — new fields
  classified and added to the masking matrix, retention category assigned, DSR impact assessed —
  next to the security checklist's PII/masking item (`.github/pull_request_template.md`).
- **DPIA:** template `docs/security/dpia-template.md`; one completed DPIA for document ingestion
  and document creation, [dpia-modocument.md](../policies/dpia-modocument.md) (draft; DPO sign-off
  outstanding).
- **PII tagging convention:** template fields carry a `PiiKind` (V100, ws34), inferred once and
  persisted when the owner has not tagged them; free text, logs and prompts are covered by one
  pattern set ([pii-tagging-convention.md](../policies/pii-tagging-convention.md);
  `src/main/java/com/modocument/domain/model/PiiKind.java`,
  `src/main/java/com/modocument/application/privacy/PiiClassifier.java`,
  `src/main/java/com/modocument/application/privacy/PiiPatterns.java`).
- **Masking engine:** role × channel matrix as data, applied at each channel's choke point and
  pinned to the documentation by `MaskingMatrixDocTest`
  (`src/main/java/com/modocument/application/privacy/MaskingPolicy.java`,
  `src/main/java/com/modocument/application/privacy/Masker.java`, `docs/security/masking-matrix.md`).
- **Pseudonymisation before AI:** identifiers are replaced by placeholders and a prompt still
  holding a raw identifier is refused (`src/main/java/com/modocument/application/privacy/PiiTokenizer.java`,
  `docs/security/ai-controls.md`).
- **Retention by default:** customer data deleted after 30 days (`docs/security/retention-and-dsr.md`).
- **Data minimisation diff:** commit `1035294` (2026-10-03, on `main` through the Go-Live Security
  Gate merge `3bc44e2`) removed the `email` and `displayName` claims from the access token in
  `src/main/java/com/modocument/infrastructure/security/JwtService.java` — tokens end up in logs
  and browser storage, and the login response already carries the profile. Shown with
  `git show 1035294 -- src/main/java/com/modocument/infrastructure/security/JwtService.java`.
- **Synthetic data only** in tests and local stacks (README writing rule 5; workbook position).

## Questionnaire answer (final)
Yes. Privacy is part of how MoDocument is built and changed. Every pull request carries a privacy
checklist (new personal-data fields classified, retention assigned, data-subject-request impact
checked). Each template field is tagged with the kind of personal data it holds, and that tag drives
role-based masking of national ID and account numbers on screens, reports and exports. Identifiers
are replaced with placeholders before any AI processing, and a prompt that still contains one is
blocked. Customer data is deleted automatically after 30 days, logs are redacted, and development
and testing use synthetic data only. A data protection impact assessment covers document ingestion
and document creation. Evidence: PRIV-004-E01.

## Evidence PRIV-004-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| PR checklist | `.github/pull_request_template.md` | repo |
| Completed DPIA (draft) | `docs/compliance/policies/dpia-modocument.md` | repo; DPO sign-off to confirm |
| PII-tag convention doc | `docs/compliance/policies/pii-tagging-convention.md` | repo |
| Diff removing an unnecessary field | commit `1035294`, `src/main/java/com/modocument/infrastructure/security/JwtService.java` | repo |
| Masking, classification, tokenisation and AI-gateway tests, 2026-10-19 | `docs/compliance/evidence/A6-test-runs.md` (batch 3) | run |

## Test PRIV-004-T01
- **Procedure:** run `MaskingGateC2Test`, `MaskingMatrixDocTest`, `MaskerTest`, `PiiClassifierTest`,
  `PiiMaskingServiceTest`, `PiiTokenizerTest` and `AiGatewayC9Test`; check the PR template, the DPIA
  and the git history for a minimisation change.
- **Expected:** API responses on masked channels show no unmasked tagged PII; no untagged PII.
- **Actual (2026-10-19):** MaskingGateC2Test 4/4, MaskingMatrixDocTest 2/2, MaskerTest 8/8,
  PiiClassifierTest 6/6, PiiMaskingServiceTest 5/5, PiiTokenizerTest 15/15, AiGatewayC9Test 6/6 —
  all pass (batch 3). PR checklist present; DPIA drafted; minimisation commit found. The workbook's
  "grep of API responses for untagged PII" across all endpoints was not run; the masking test
  samples the read-only screen, report and export channels only. MoDocument's own fixed columns
  (user e-mail, names) are not annotated in the entities.
- **Result:** Partial.
- **Tester:** Agent A6 (automated re-performance); human sign-off required. **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| DPIA not signed off | Org | DPO review, ratings and decision; risk-owner approval | the DPO | `TO CONFIRM (organisational)` | Day 30 |
| No API-wide scan for untagged PII | Build | Add a test that seeds canary PII through ingestion and document creation, calls every GET route in `docs/api/openapi-routes.txt` as each role, and fails on an unmasked canary outside the working channels | Software Engineering — Bhavish Nobeen | 2026-11-20 (proposed) | Day 60 |
| Fixed columns with personal data are listed, not annotated | Build | Add a `@PersonalData` marker on entity fields and a test that matches it to the data inventory | Software Engineering — Bhavish Nobeen | 2027-01-15 (proposed) | Day 90 |
| Data inventory out of date (last reviewed 2026-10-09; lacks ws25–ws34 stores) | Doc | Update `docs/security/data-inventory.md` | Software Engineering — Bhavish Nobeen | 2026-11-06 (proposed) | Day 30 |

## Risk R-PRIV-004
Unnecessary personal data accumulates across the stack, enlarging every breach, DSR and retention
problem. Cause in the workbook: no systematic PII tagging, so masking, logging and DSR tooling
cannot find the fields. Tagging, pattern-based detection and the 30-day purge now exist; the open
part is the API-wide proof. Likelihood, impact and residual are set in MO-031.

## To confirm
- `TO CONFIRM (organisational)`: DPO review and sign-off of `dpia-modocument.md`, with the risk owner's approval.
- `TO CONFIRM (organisational)`: production language-model provider and its data-handling terms (DPIA §2).
