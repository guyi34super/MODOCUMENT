# SUP-S10 — Email and company-domain security

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 04 IT Infrastructure | IT Infrastructure & Operations | P1 | UNKNOWN - VALIDATION REQUIRED | SUP-S10-E01 | SUP-S10-T01 | R-SUP-S10 |

## Requirement
Harden the e-mail channel used to exchange the questionnaire and reports
with the bank: SPF and DKIM for all senders, DMARC moving from `p=none`
with reports to quarantine/reject. Acceptance: a DMARC test shows spoofed
mail from the company domain rejected.

## How MoDocument meets it today
- **Nothing in the repository can prove or disprove this.** The company
  e-mail domain and its DNS are not managed in Terraform: there are no
  Route 53 zones or records at all (search of `infra/terraform/`, TF-110 in
  [A8-infra-checks.md](../evidence/A8-infra-checks.md)). The root
  `README.md` "Implement later" table lists SUP-S10 as DNS-level with no
  repo artifact (owner Taj Aundoo), and `docs/security/security-roadmap.md`
  says the same.
- **Workbook position (2 Oct 2026):** the domain was unprotected; SPF,
  DKIM and DMARC were to be set up "this week" because MauBank due
  diligence correspondence was under way. That date has passed; whether it
  was done is `TO CONFIRM (organisational)`. Status is therefore
  **UNKNOWN - VALIDATION REQUIRED**, not Not Implemented.
- **Related in the repository:** the security contact is published
  (`SECURITY.md`; `frontend/public/.well-known/security.txt`), which
  needs the security@ mailbox (gate E5) — `TO CONFIRM (organisational)`.
- **Secure-exchange agreement** with the bank (how questionnaires and
  reports are exchanged, encrypted): `TO CONFIRM (organisational)`.
- **DKIM keys** are listed in the key register as K23
  (`docs/security/key-certificate-register.md`).

## Evidence SUP-S10-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| DMARC record at enforcement (screenshot) and a DMARC report digest | DNS provider / DMARC report mailbox | `TO CONFIRM (organisational)` |
| SPF and DKIM records for every sender | DNS provider, mail provider | `TO CONFIRM (organisational)` |
| Secure-exchange agreement with the bank | Contract / correspondence | `TO CONFIRM (organisational)` |
| Absence of DNS in IaC | [A8-infra-checks.md](../evidence/A8-infra-checks.md) TF-110 | run (2026-10-19) |

## Test SUP-S10-T01
- **Procedure:** look up the domain's SPF, DKIM selector and DMARC TXT
  records (`dig TXT <domain>`, `dig TXT _dmarc.<domain>`); send a spoofed
  test message to a mailbox at the bank or a DMARC tester; read the
  verdict.
- **Expected result:** spoofed mail from the company domain is rejected.
- **Actual result:** not run — the company domain is not named in the
  repository, and A8 does not guess it.
- **Result:** Not run.
- **Tester:** Agent A8; human sign-off required.
- **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| SPF/DKIM/DMARC state unknown; workbook date ("this week" of 2 Oct 2026) passed | Config | Confirm or publish SPF (`-all` after auditing senders), DKIM on all senders, DMARC `p=none` with reports, then quarantine/reject; MTA-STS and TLS-RPT; registrar MFA and transfer lock | IT Infrastructure — Bhavish Nobeen (README: Taj Aundoo) | **Overdue**; 2026-10-30 proposed | Day 30 |
| Secure-exchange agreement | Org | Agree the exchange method with MauBank | `TO CONFIRM (organisational)` | 2026-10-30 proposed | Day 30 |
| Lookalike-domain monitoring | Config | Monthly lookalike-domain check | Bhavish Nobeen | `TO CONFIRM (organisational)` | Day 60 |

## Risk R-SUP-S10
- **Risk:** attackers phish the bank "from" the MO Intelligence domain
  during due diligence (fake instructions or links).
- **Cause:** SPF/DKIM/DMARC not configured, or DMARC left at `p=none`.
- **Mitigation:** DMARC at enforcement, monthly report review, agreed
  secure exchange channel. Likelihood, impact and residual are set in
  MO-031.

## To confirm
- `TO CONFIRM (organisational)`: company e-mail domain; current SPF, DKIM and DMARC records; DMARC report mailbox; secure-exchange agreement; owner (workbook: Bhavish Nobeen; README: Taj Aundoo); security@ mailbox live (gate E5).
