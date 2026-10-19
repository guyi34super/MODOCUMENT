# SUP-S09 — Endpoint, device and physical security

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 04 IT Infrastructure | IT Infrastructure & Operations | P2 | Not Implemented | SUP-S09-E01 | SUP-S09-T01 | R-SUP-S09 |

## Requirement
Secure the laptops and premises that touch bank data (Bank of Mauritius
guideline physical-security and people clauses). Acceptance: MDM shows
100 % encryption and EDR compliance for in-scope staff.

## How MoDocument meets it today
This control is organisational; the repository can prove very little.

- **Policy:** a device and BYOD policy is drafted and marked **proposed —
  requires management approval**:
  [endpoint-device-policy.md](../policies/endpoint-device-policy.md).
- **MDM, disk encryption, EDR:** none chosen or deployed (workbook, 2 Oct
  2026: "Planned before go-live"). `TO CONFIRM (organisational)`.
- **What the repository does contribute:**
  - no static cloud keys are needed on laptops for deployment — CI deploys
    through GitHub OIDC (`infra/terraform/modules/ci-oidc/main.tf`);
  - local development needs no shared secrets: compose requires
    per-machine generated values in a gitignored `.env`
    (`docs/security/key-certificate-register.md`, local-only section);
  - secret scanning in CI stops keys from being committed
    (`.github/workflows/security.yml`).
- **Today laptops hold synthetic data only** (workbook statement, 2 Oct
  2026) — `TO CONFIRM (organisational)`.
- **Physical security:** no production system is on MO premises;
  production is in AWS af-south-1
  (`infra/terraform/envs/production/providers.tf`). Office access
  control: `TO CONFIRM (organisational)`.

## Evidence SUP-S09-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Device/BYOD policy (draft) | [endpoint-device-policy.md](../policies/endpoint-device-policy.md) | repo (proposed) |
| MDM compliance report | MDM console | `TO CONFIRM (organisational)` — no MDM |
| Premises access note | — | `TO CONFIRM (organisational)` |

## Test SUP-S09-T01
- **Procedure:** export the MDM compliance report for all in-scope staff;
  check encryption, screen lock, OS updates and EDR status per device.
- **Expected result:** 100 % encryption and EDR compliance for in-scope
  staff.
- **Actual result:** not possible — no MDM exists.
- **Result:** Not run.
- **Tester:** Agent A8; human sign-off required.
- **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| No MDM, encryption enforcement or EDR | Buy/Config | Choose an MDM and EDR; enrol every in-scope device | IT Infrastructure — Bhavish Nobeen | Pre-go-live, date to set (`TO CONFIRM (organisational)`) | Day 30 (workbook roadmap: P1 before go-live) |
| No approved device/BYOD policy | Org | Approve the draft (choose the BYOD position) | Approver `TO CONFIRM (organisational)` | `TO CONFIRM (organisational)` | Day 30 |
| No premises note | Org | Write the premises access note | `TO CONFIRM (organisational)` | `TO CONFIRM (organisational)` | Day 60 |
| Policy draft | Doc | Written | Agent A8 | 2026-10-19 | Done |

## Risk R-SUP-S09
- **Risk:** a stolen unencrypted laptop is a reportable breach of bank
  data.
- **Cause:** unmanaged devices (including personal ones) reach production
  or bank data.
- **Mitigation:** MDM with enforced encryption, EDR, remote wipe, BYOD
  prohibition for bank data. Likelihood, impact and residual are set in
  MO-031.

## To confirm
- `TO CONFIRM (organisational)`: MDM and EDR products; device inventory of in-scope staff; BYOD position; premises access control; that laptops currently hold synthetic data only; policy approver.
