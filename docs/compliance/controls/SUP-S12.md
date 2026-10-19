# SUP-S12 — Vulnerability disclosure and threat intelligence

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 02 Security | Security | P2 | Partially Implemented | SUP-S12-E01 | SUP-S12-T01 | R-SUP-S12 |

## Requirement
Receive external vulnerability reports gracefully and watch the threat
landscape. Acceptance: a test report to the security contact gets an
acknowledgement inside the SLA.

## How MoDocument meets it today
The owner statement of 2 Oct 2026 said there was no security.txt and no
disclosure policy. Both now exist in the repository; the mailbox does not.

- **security.txt (RFC 9116):** `frontend/public/.well-known/security.txt`
  with `Contact`, `Expires` (2027-10-04, less than a year ahead),
  `Preferred-Languages`, `Canonical` and `Policy`. nginx serves it from the
  `location /` block of `frontend/nginx.conf` with the full security-header
  set (`scripts/check-nginx-headers.sh` OK, 2026-10-19). The `Contact`
  address `security@modocument.example` is a placeholder on a reserved
  domain; `Canonical` uses the same placeholder domain; `Policy` points at a
  GitHub URL of a private repository that outside reporters cannot open.
- **Disclosure policy:** `docs/compliance/policies/vulnerability-disclosure-policy.md`
  (one page: how to report, 48-hour acknowledgement, 7-day triage, fix SLAs,
  90-day coordinated disclosure, safe harbour, scope, internal handling). The
  public summary is `SECURITY.md` at the repository root (2 business days to
  acknowledge; the policy uses the workbook's stricter 48 hours).
- **Threat intelligence:** weekly feeds are automated — Dependabot for
  Maven, npm, Actions, Docker and Terraform (`.github/dependabot.yml`),
  weekly image rebuild with Trivy that opens an issue
  (`.github/workflows/image-rebuild.yml`), weekly Security workflow and ZAP
  baseline. The human weekly review is now written down:
  `docs/compliance/policies/threat-intelligence-review-procedure.md` with a
  note template, and a first note for week 43:
  `docs/compliance/evidence/A5-threat-intel-review-note-2026-W43.md`. That
  first review found that CI has not run since at least 2026-10-17 and that
  11 critical library CVEs are past their SLA (see SEC-009).
- **CERT-MU subscription:** not evidenced.

## Evidence SUP-S12-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| security.txt | `frontend/public/.well-known/security.txt` | repo; live at the production domain `NOT IN REPO (live cloud)` |
| Disclosure policy | `docs/compliance/policies/vulnerability-disclosure-policy.md`, `SECURITY.md` | repo |
| Weekly-review procedure and first note | `docs/compliance/policies/threat-intelligence-review-procedure.md`, `docs/compliance/evidence/A5-threat-intel-review-note-2026-W43.md` | repo + run 2026-10-19 |
| Live mailbox and acknowledgement test | — | `TO CONFIRM (organisational)` |

## Test SUP-S12-T01
- **Procedure:** send a test report to the contact in `security.txt`;
  measure the time to acknowledgement against the 48-hour SLA. Check the
  RFC 9116 fields of the file.
- **Expected result:** a test report to the security contact gets an
  acknowledgement inside the SLA.
- **Actual result:** not performed — the contact is a placeholder on the
  reserved `.example` domain and no mailbox exists. Field check: required
  fields `Contact` and `Expires` present, `Expires` within one year; the file
  is served with the security headers; `Policy` URL not publicly reachable.
- **Result:** Not run · tester: Agent A5 (automated re-performance; human
  sign-off required) · date: 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| No real security address or mailbox | Org | Create the mailbox to all directors, put the real address in `security.txt` and `SECURITY.md`, send a test report and record the acknowledgement time | Taj Aundoo (mailbox, per `SECURITY.md`) / Security — Bhavish Nobeen (per workbook) — split `TO CONFIRM (organisational)` | 2026-10-30 (proposed; workbook 2026-10-16 passed) | Day 30 |
| `Policy` / `Canonical` not public | Config | Publish the policy at a public URL on the production domain; update both lines | Security — Bhavish Nobeen | with the production domain (`TO CONFIRM (organisational)`) | Pre-go-live |
| CERT-MU advisories not subscribed | Org | Subscribe; add to the weekly review | Security — Bhavish Nobeen | 2026-10-30 (proposed) | Day 30 |
| Policy not approved | Org | Approve the policy and the review procedure | Reezvi Pydiah | 2026-10-30 (proposed) | Day 30 |

## Risk R-SUP-S12
A reporter with a critical finding has no safe route and goes public; or a
published vulnerability in our stack goes unnoticed. Cause: contact and
review never set up. Mitigation: live mailbox with 48-hour acknowledgement;
weekly review with the automated feeds. Likelihood, impact and residual are
set in MO-031.

## To confirm
- `TO CONFIRM (organisational)`: the real security contact address and mailbox; who owns it (Taj Aundoo per `SECURITY.md`, Bhavish Nobeen per the workbook).
- `TO CONFIRM (organisational)`: the production domain for `Canonical` and the public policy URL; legal review of the safe-harbour wording; CERT-MU subscription.
- `NOT IN REPO (live cloud)`: `security.txt` served at the production domain.
