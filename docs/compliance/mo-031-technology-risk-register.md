# MO-031 — Technology Risk Register

**Task MO-031: Technology risk register (joint workshop).** Deliverable: Technology Risk Register.
**Linked DPO task:** MO-048 (privacy risk input to the technology risk register).

This register rates every risk in the Risk Register sheets of the three technology-risk workbooks (02 Security, 03 Software Engineering, 04 IT Infrastructure & Operations: 64 rows, their IDs kept) and adds 16 risks (`R-TECH-01`…) for the checklist themes the workbooks do not cover and for problems the other pack documents found. The same data is in `docs/compliance/risks.yaml`, which the workbook updater writes into the Likelihood, Impact, Residual Risk, Status, Mitigation and Acceptance Required columns. The residual rating of each `R-<CID>` risk is also in the `risk:` block of `docs/compliance/controls/<CID>.yaml`.

Role names follow the RACI in `docs/compliance/mo-001-governance-structure.md` (row 19). The management document that sets the method, appetite and reporting is `docs/compliance/mo-032-technology-risk-management.md`.

## 1. Document control

| Field | Value |
|---|---|
| Document | MO-031 Technology Risk Register |
| Version | 1.0 draft |
| Date | 2026-10-19 |
| Status | Draft for the joint workshop. Not yet reviewed. |
| Responsible | Bhavish Nobeen (RACI row 19: R). Drafted for the pack by agent A9. |
| Accountable (risk and control review) | Taj Aundoo (RACI row 19: A) |
| Consulted | Reezvi Pydiah, Rushal, the DPO (privacy and data-protection risks, MO-048) |
| Informed | Board of Directors (quarterly report, MO-032 §12) |
| Joint workshop | The checklist asks for a joint session with all directors and the DPO. Date and attendees: `TO CONFIRM (organisational)`. This draft is the input to that session; every rating is a proposal until the workshop confirms it. |
| Review cycle | Quarterly, and after any incident, stress test, penetration test or go-live decision (MO-032 §4) |

| Version | Date | Change | By |
|---|---|---|---|
| 1.0 draft | 2026-10-19 | First register: 64 workbook risks rated, new risks added from the checklist themes and the pack findings | Bhavish Nobeen (owner); drafted for the pack by agent A9 |

## 2. Method

The method is set in MO-032 §3. In short:

- **Score = Likelihood × Impact** on two 1–5 scales.
- **Bands:** 1–4 **Low**, 5–9 **Medium**, 10–16 **High**, 20–25 **Critical**.
- **Inherent** = the risk with no control in place.
- **Residual** = the risk with the controls this pack has proven on 2026-10-19 (each row names its proof). A control that exists only as unapplied Terraform, or only on paper, lowers the residual rating only as far as the pack shows it would work.
- **Rating date and horizon.** Ratings are made on 2026-10-19 for the next 12 months. On 2 Oct 2026 the workbooks recorded MoDocument as pre-production: nothing deployed, no bank data. The register rates the exposure at the first client go-live with today's controls, because that is the decision the Board will take. Until go-live, the actual exposure is lower.
- **Acceptance required = Yes** when the residual band is High or Critical, or when the workbook already asks for an acceptance. Acceptances are decided by Reezvi Pydiah (approval matrix D11); High and Critical also go to the Board (proposed — confirm).
- **DPO input** marks privacy and data-protection risks. The DPO's own input (MO-048) has not been received: `TO CONFIRM (organisational)`.

| Likelihood | Meaning (next 12 months) |
|---|---|
| 1 Rare | Less than 5 % chance; needs several independent failures |
| 2 Unlikely | 5–20 %; a control exists and is tested, a gap remains |
| 3 Possible | 20–50 %; controls designed but not operating or not tested |
| 4 Likely | 50–90 %; a known gap with no working control |
| 5 Almost certain | Above 90 %, or already happening today |

| Impact | Meaning |
|---|---|
| 1 Insignificant | No client effect; internal rework only |
| 2 Minor | Small internal effect; no bank data exposed; no SLA breach |
| 3 Moderate | Limited, contained exposure or outage shorter than the RTO; audit finding; one bank affected briefly |
| 4 Major | Exposure of one bank's personal data, outage longer than the RTO, failed due diligence or a regulator finding |
| 5 Severe | Personal data of several banks or at scale exposed, loss of data, regulator notification and loss of a client |

## 3. Heat maps

Each cell shows the number of risks and the band of the cell (L = Low, M = Medium, H = High, C = Critical).

### 3.1 Inherent

| Impact ↓ / Likelihood → | 1 Rare | 2 Unlikely | 3 Possible | 4 Likely | 5 Almost certain |
|---|---|---|---|---|---|
| **5 Severe** | 1 (M) | 1 (H) | 9 (H) | 10 (C) | 1 (C) |
| **4 Major** | – (L) | 2 (M) | 24 (H) | 13 (H) | 2 (C) |
| **3 Moderate** | – (L) | – (M) | 9 (M) | 6 (H) | 1 (H) |
| **2 Minor** | – (L) | – (L) | 1 (M) | – (M) | – (H) |
| **1 Insignificant** | – (L) | – (L) | – (L) | – (L) | – (M) |

### 3.2 Residual

| Impact ↓ / Likelihood → | 1 Rare | 2 Unlikely | 3 Possible | 4 Likely | 5 Almost certain |
|---|---|---|---|---|---|
| **5 Severe** | 3 (M) | 2 (H) | 1 (H) | 1 (C) | – (C) |
| **4 Major** | 6 (L) | 14 (M) | 13 (H) | 3 (H) | 1 (C) |
| **3 Moderate** | 3 (L) | 14 (M) | 15 (M) | 3 (H) | – (H) |
| **2 Minor** | 1 (L) | – (L) | – (M) | – (M) | – (H) |
| **1 Insignificant** | – (L) | – (L) | – (L) | – (L) | – (M) |

### 3.3 Totals

| Band | Inherent | Residual |
|---|---|---|
| Critical | 13 | 2 |
| High | 54 | 22 |
| Medium | 13 | 46 |
| Low | 0 | 10 |
| **Total** | **80** | **80** |

Residual High and Critical risks, by cell:

| Cell (L × I) | Score | Risks |
|---|---|---|
| 4 × 5 | 20 Critical | R-TECH-08 |
| 5 × 4 | 20 Critical | R-TECH-09 |
| 4 × 4 | 16 High | R-SEC-032, R-PEOPLE-02, R-SEC-010 |
| 3 × 5 | 15 High | R-PRIV-006 |
| 3 × 4 | 12 High | R-SEC-009, R-SEC-017, R-SEC-025, R-AI-01, R-SEC-026, R-SUP-S09, R-SUP-S10, R-SPOOF-01, R-TECH-01, R-TECH-02, R-TECH-04, R-TECH-06, R-TECH-11 |
| 4 × 3 | 12 High | R-SCHED-01, R-TECH-12, R-TECH-13 |
| 2 × 5 | 10 High | R-SUP-S05, R-PRIV-003 |

## 4. Top residual risks

Sorted by residual score, then impact. MO-032 §8 carries the treatment plans of the top ten.

| Rank | ID | Risk | Residual | Owner | Target date |
|---|---|---|---|---|---|
| 1 | R-TECH-08 | Eleven critical library CVEs unpatched past the 72-hour limit (issue #52) | 4×5 = 20 **Critical** | IT Infrastructure — Bhavish Nobeen | 2026-10-22 (proposed; overdue since 2026-10-15) |
| 2 | R-TECH-09 | CI not running and branch protection not enforced | 5×4 = 20 **Critical** | Software Engineering — Bhavish Nobeen | 2026-10-22 (CI), 2026-10-30 (ruleset) (proposed) |
| 3 | R-PEOPLE-02 | One person owns almost every remediation action | 4×4 = 16 **High** | Security — Bhavish Nobeen | 2026-11-02 (proposed) |
| 4 | R-SEC-032 | Attacks proceed for days; first notice comes from the bank | 4×4 = 16 **High** | Security — Bhavish Nobeen | 2026-10-30 (overdue workbook date 2026-10-16) |
| 5 | R-SEC-010 | Vulnerabilities and secrets merge unexamined | 4×4 = 16 **High** | Software Engineering — Bhavish Nobeen | 2026-10-30 (proposed; workbook 2026-10-24) |
| 6 | R-PRIV-006 | Backup failure discovered only during a real incident | 3×5 = 15 **High** | IT Infrastructure — Bhavish Nobeen | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| 7 | R-SEC-009 | Exploitable vulnerabilities unknown to us but findable by attackers | 3×4 = 12 **High** | Security — Bhavish Nobeen | 2026-10-31 (budget decision) |
| 8 | R-SEC-017 | Unknown or shared admin accounts operate unwatched | 3×4 = 12 **High** | Security — Bhavish Nobeen | 2026-10-30 |
| 9 | R-SEC-026 | Known-exploited vulnerabilities stay open for months | 3×4 = 12 **High** | IT Infrastructure — Bhavish Nobeen | 2026-10-22 |
| 10 | R-AI-01 | Non-adopted AI controls: prompt injection, model-written figures, untested model changes | 3×4 = 12 **High** | Software Engineering — Bhavish Nobeen | 2026-11-02 |

## 5. Register

One row per risk. `WB 02/03/04` = workbook risk (ID kept); `New` = added by this register. Ratings are L × I = score and band. Causes, current controls, actions and dates are in §6.

| ID | Risk | Source | Theme | Controls | Risk owner | Inherent | Residual | Treatment | Status | Acceptance required | DPO input |
|---|---|---|---|---|---|---|---|---|---|---|---|
| R-PRIV-002 | Bulk data theft proceeds unnoticed | WB 02 | Data loss | PRIV-002, SEC-032 | Security — Bhavish Nobeen | 4×5 = 20 Critical | 2×4 = 8 **Medium** | Mitigate | Open | No | Yes |
| R-PRIV-011 | One CIA pillar left unmanaged | WB 02 | Security | PRIV-011 | Security — Bhavish Nobeen | 3×3 = 9 Medium | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SEC-009 | Exploitable vulnerabilities unknown to us but findable by attackers | WB 02 | Security | SEC-009, SEC-026 | Security — Bhavish Nobeen | 4×5 = 20 Critical | 3×4 = 12 **High** | Mitigate | Open | Yes | No |
| R-SEC-013 | A compromised ordinary account yields broad data access | WB 02 | Access | SEC-013, SEC-011 | Security — Bhavish Nobeen | 3×4 = 12 High | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SEC-015 | Admin abuse or error cannot be reconstructed | WB 02 | Security | SEC-015, SEC-035 | Security — Bhavish Nobeen | 3×4 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | No |
| R-SEC-017 | Unknown or shared admin accounts operate unwatched | WB 02 | Access | SEC-017, SEC-013 | Security — Bhavish Nobeen | 4×5 = 20 Critical | 3×4 = 12 **High** | Mitigate | Open | Yes | No |
| R-SEC-031 | The bank is blind to events inside the platform | WB 02 | Security | SEC-031, SEC-034 | Security — Bhavish Nobeen | 3×3 = 9 Medium | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SEC-031b | Bank SIEM feed contains other banks' events | WB 02 | Security | SEC-031 | Security — Bhavish Nobeen | 3×5 = 15 High | 1×5 = 5 **Medium** | Avoid | Open (avoided: no bank SIEM connected) | No — Workbook: "No — avoid instead". | No |
| R-SEC-032 | Attacks proceed for days; first notice comes from the bank | WB 02 | Security | SEC-032, SUP-S11 | Security — Bhavish Nobeen | 4×5 = 20 Critical | 4×4 = 16 **High** | Mitigate | Open | Yes | No |
| R-SEC-040 | Threats without a detective control; roles with unreviewed power | WB 02 | Security | SEC-040 | Security — Bhavish Nobeen | 3×4 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | No |
| R-SEC-041 | Gaps acknowledged but never scheduled | WB 02 | Security | SEC-041 | Security — Bhavish Nobeen | 3×3 = 9 Medium | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SUP-S12 | A reporter with a critical finding has no safe route | WB 02 | Security | SUP-S12 | Security — Bhavish Nobeen | 3×3 = 9 Medium | 3×3 = 9 **Medium** | Mitigate | Open | No | No |
| R-PEOPLE-02 | One person owns almost every remediation action | WB 02 | Key-person dependency | SEC-041 | Security — Bhavish Nobeen | 5×4 = 20 Critical | 4×4 = 16 **High** | Accept + mitigate | Accepted (management decision) | Yes — Recorded by management on 2 Oct 2026; re-confirm at Board level because the residual is High. | No |
| R-PRIV-004 | Unnecessary personal data accumulates | WB 03 | Regulatory / privacy | PRIV-004 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 2×3 = 6 **Medium** | Mitigate | Open | No | Yes |
| R-PRIV-013 | An unanswered subject request becomes a complaint naming the bank | WB 03 | Regulatory / privacy | PRIV-013 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 2×4 = 8 **Medium** | Mitigate | Open | No | Yes |
| R-PRIV-017 | Ex-staff or over-privileged accounts keep access | WB 03 | Access | PRIV-017, SEC-013 | Software Engineering — Bhavish Nobeen | 3×5 = 15 High | 2×4 = 8 **Medium** | Mitigate | Open | No | Yes |
| R-SEC-006 | Shadow interfaces without security review | WB 03 | Security | SEC-006 | Software Engineering — Bhavish Nobeen | 3×3 = 9 Medium | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SEC-007 | Broken object-level authorisation or unthrottled endpoints leak data | WB 03 | Security | SEC-007, SEC-011 | Software Engineering — Bhavish Nobeen | 4×5 = 20 Critical | 2×4 = 8 **Medium** | Mitigate | Open | No | No |
| R-SEC-008 | Capacity discovered in production during a real peak | WB 03 | Availability / performance | SEC-008, SEC-002, SEC-003 | Software Engineering — Bhavish Nobeen | 4×4 = 16 High | 2×4 = 8 **Medium** | Mitigate | Open | No | No |
| R-SEC-010 | Vulnerabilities and secrets merge unexamined | WB 03 | Security | SEC-010, SEC-025, SUP-S05 | Software Engineering — Bhavish Nobeen | 4×4 = 16 High | 4×4 = 16 **High** | Mitigate | Open | Yes | No |
| R-SEC-011 | Unmapped endpoints default open | WB 03 | Access | SEC-011 | Software Engineering — Bhavish Nobeen | 4×5 = 20 Critical | 1×4 = 4 **Low** | Mitigate | Open (control effective) | No | No |
| R-SEC-012 | Staff receive supervisor rights because no finer role exists | WB 03 | Access | SEC-012 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 1×3 = 3 **Low** | Mitigate | Open (control effective) | No | No |
| R-SEC-014 | One phished password gives admin takeover | WB 03 | Access | SEC-014 | Software Engineering — Bhavish Nobeen | 4×5 = 20 Critical | 2×4 = 8 **Medium** | Mitigate | Open | No | No |
| R-SEC-016 | Reports export what the UI would deny | WB 03 | Access | SEC-016 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 1×4 = 4 **Low** | Mitigate | Open (control effective) | No | No |
| R-SEC-018 | A repository leak yields production credentials | WB 03 | Security | SEC-018 | Software Engineering — Bhavish Nobeen | 3×5 = 15 High | 1×5 = 5 **Medium** | Mitigate | Open | No | No |
| R-SEC-019 | Log aggregation becomes a password database | WB 03 | Security | SEC-019 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 1×4 = 4 **Low** | Mitigate | Open (control effective) | No | No |
| R-SEC-020 | Insider impersonation of users without trace | WB 03 | Access | SEC-020 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SEC-021 | Password spraying succeeds | WB 03 | Access | SEC-021 | Software Engineering — Bhavish Nobeen | 4×4 = 16 High | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SEC-022 | A stolen reset link or OTP works days later | WB 03 | Access | SEC-022 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SEC-023 | One user changes limits or configuration alone | WB 03 | Access | SEC-023 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 1×4 = 4 **Low** | Mitigate | Open (control effective) | No | No |
| R-SEC-024 | Toxic permission combinations accumulate | WB 03 | Access | SEC-024 | Software Engineering — Bhavish Nobeen | 3×3 = 9 Medium | 1×3 = 3 **Low** | Mitigate | Open (control effective) | No | No |
| R-SEC-025 | Untraceable production changes; failed changes without rollback | WB 03 | Security | SEC-025, SUP-S05 | Software Engineering — Bhavish Nobeen | 4×4 = 16 High | 3×4 = 12 **High** | Mitigate | Open | Yes | No |
| R-SEC-027 | Capabilities presented inconsistently | WB 03 | Security | SEC-027 | Software Engineering — Bhavish Nobeen | 3×2 = 6 Medium | 1×2 = 2 **Low** | Mitigate | Open (control effective) | No | No |
| R-SEC-028 | An unencrypted hop or backup becomes the plaintext source | WB 03 | Security | SEC-028, SEC-029 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SEC-029 | Legacy cryptography persists unnoticed | WB 03 | Security | SEC-029 | Software Engineering — Bhavish Nobeen | 2×4 = 8 Medium | 1×3 = 3 **Low** | Mitigate | Open | No | No |
| R-SEC-034 | An investigation cannot connect call, session and data change | WB 03 | Security | SEC-034 | Software Engineering — Bhavish Nobeen | 3×3 = 9 Medium | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SEC-035 | A malicious admin edits history through the application account | WB 03 | Security | SEC-035 | Software Engineering — Bhavish Nobeen | 3×5 = 15 High | 1×4 = 4 **Low** | Mitigate | Open | No | No |
| R-SEC-042 | A Log4Shell-class event with no way to know exposure | WB 03 | Security | SEC-042 | Software Engineering — Bhavish Nobeen | 4×4 = 16 High | 2×4 = 8 **Medium** | Mitigate | Open | No | No |
| R-SUP-S01 | Session hijack or script injection in the bank-facing UI | WB 03 | Security | SUP-S01 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SUP-S02 | A leaked token outlives the compromise | WB 03 | Security | SUP-S02 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SUP-S05 | A compromised CI secret or action deploys attacker code | WB 03 | Security | SUP-S05, SEC-025 | Software Engineering — Bhavish Nobeen | 3×5 = 15 High | 2×5 = 10 **High** | Mitigate | Open | Yes | No |
| R-SUP-S07 | A dev-environment compromise reaches production data | WB 03 | Hosting / infrastructure | SUP-S07 | Software Engineering — Bhavish Nobeen | 3×5 = 15 High | 2×4 = 8 **Medium** | Mitigate | Open | No | No |
| R-SUP-S13 | Bank data leaves jurisdiction into a model provider; cross-tenant leakage through model output | WB 03 | Third-party and AI providers | SUP-S13, PRIV-002 | Software Engineering — Bhavish Nobeen | 4×5 = 20 Critical | 2×4 = 8 **Medium** | Mitigate | Open | No | Yes |
| R-AI-01 | Non-adopted AI controls: prompt injection, model-written figures, untested model changes | WB 03 | AI output accuracy | SUP-S13 | Software Engineering — Bhavish Nobeen | 4×4 = 16 High | 3×4 = 12 **High** | Accept | Pending written acceptance | Yes — Workbook: Reezvi Pydiah (gate C11). | No |
| R-SHARED-01 | Team shares one pre-production account | WB 03 | Access | SEC-011, SEC-020, SEC-035 | Software Engineering — Bhavish Nobeen | 4×3 = 12 High | 3×3 = 9 **Medium** | Accept (interim) | Accepted (interim, to 21 Oct) | Yes — Workbook: Reezvi Pydiah. The interim acceptance ends on 2026-10-21. | No |
| R-SCHED-01 | Many due dates fall on one owner at once | WB 03 | Key-person dependency | SEC-041 | Software Engineering — Bhavish Nobeen | 5×3 = 15 High | 4×3 = 12 **High** | Mitigate | Open | Yes — Workbook: "Recorded". | No |
| R-PRIV-003 | One compromised admin credential encrypts production and backups | WB 04 | Data loss | PRIV-003, PRIV-006 | IT Infrastructure — Bhavish Nobeen | 3×5 = 15 High | 2×5 = 10 **High** | Mitigate | Open | Yes | No |
| R-PRIV-006 | Backup failure discovered only during a real incident | WB 04 | Data loss | PRIV-006, SEC-004 | IT Infrastructure — Bhavish Nobeen | 4×5 = 20 Critical | 3×5 = 15 **High** | Mitigate | Open | Yes | No |
| R-SEC-001 | Undocumented architecture hides single points of failure | WB 04 | Hosting / infrastructure | SEC-001 | IT Infrastructure — Bhavish Nobeen | 3×3 = 9 Medium | 2×3 = 6 **Medium** | Mitigate | Open | No | No |
| R-SEC-002 | A traffic spike cascades into full outage | WB 04 | Availability / performance | SEC-002, SUP-S03 | IT Infrastructure — Bhavish Nobeen | 3×4 = 12 High | 2×4 = 8 **Medium** | Mitigate | Open | No | No |
| R-SEC-003 | Saturation during peaks | WB 04 | Availability / performance | SEC-003, SEC-008 | IT Infrastructure — Bhavish Nobeen | 3×4 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | No |
| R-SEC-004 | A zone or node failure causes an outage | WB 04 | Availability / performance | SEC-004 | IT Infrastructure — Bhavish Nobeen | 3×4 = 12 High | 2×4 = 8 **Medium** | Mitigate | Open | No | No |
| R-REGION-01 | Loss of the whole AWS Cape Town region | WB 04 | Hosting / infrastructure | SEC-004, PRIV-006 | IT Infrastructure — Bhavish Nobeen | 1×5 = 5 Medium | 1×5 = 5 **Medium** | Accept | Pending written acceptance | Yes — Workbook: Reezvi Pydiah. | No |
| R-SEC-005 | Disputes over outages without an authoritative measurement | WB 04 | Availability / performance | SEC-005, SUP-S11 | IT Infrastructure — Bhavish Nobeen | 4×3 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | No |
| R-SEC-026 | Known-exploited vulnerabilities stay open for months | WB 04 | Security | SEC-026, SEC-009 | IT Infrastructure — Bhavish Nobeen | 4×5 = 20 Critical | 3×4 = 12 **High** | Mitigate | Open | Yes | No |
| R-SEC-033 | Evidence needed for an investigation has rotated away | WB 04 | Security | SEC-033, SEC-035 | IT Infrastructure — Bhavish Nobeen | 4×3 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | No |
| R-SUP-S03 | A rented DDoS takes the service offline | WB 04 | Availability / performance | SUP-S03, SEC-002 | IT Infrastructure — Bhavish Nobeen | 3×4 = 12 High | 2×4 = 8 **Medium** | Mitigate | Open | No | No |
| R-SUP-S04 | One compromised container pivots across the cluster | WB 04 | Hosting / infrastructure | SUP-S04 | IT Infrastructure — Bhavish Nobeen | 2×4 = 8 Medium | 1×4 = 4 **Low** | Mitigate | Open | No | No |
| R-SUP-S06 | A reachable database or shared account defeats application controls | WB 04 | Hosting / infrastructure | SUP-S06, SEC-013 | IT Infrastructure — Bhavish Nobeen | 3×5 = 15 High | 2×4 = 8 **Medium** | Mitigate | Open | No | No |
| R-SUP-S08 | A forgotten certificate expires; a lost key orphans data | WB 04 | Hosting / infrastructure | SUP-S08 | IT Infrastructure — Bhavish Nobeen | 3×4 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | No |
| R-SUP-S09 | A stolen unencrypted laptop is a reportable breach | WB 04 | Security | SUP-S09 | IT Infrastructure — Bhavish Nobeen | 3×4 = 12 High | 3×4 = 12 **High** | Mitigate | Open | Yes | No |
| R-SUP-S10 | Attackers phish the bank from our domain | WB 04 | Security | SUP-S10 | IT Infrastructure — Bhavish Nobeen | 3×4 = 12 High | 3×4 = 12 **High** | Mitigate | Open | Yes | No |
| R-SPOOF-01 | Company domain spoofed during MauBank due diligence | WB 04 | Security | SUP-S10 | IT Infrastructure — Bhavish Nobeen | 3×4 = 12 High | 3×4 = 12 **High** | Mitigate | Open | Yes — Workbook: "No"; the residual is High, so this register asks for an acceptance until DMARC is live. | No |
| R-SUP-S11 | Silent degradation; the bank learns first | WB 04 | Availability / performance | SUP-S11, SEC-005 | IT Infrastructure — Bhavish Nobeen | 4×3 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | No |
| R-TECH-01 | Go-live environment never built or rehearsed | New | Hosting / infrastructure | SEC-001, SEC-004, SUP-S07, PRIV-006 | IT Infrastructure — Bhavish Nobeen | 4×4 = 16 High | 3×4 = 12 **High** | Mitigate | Open | Yes | No |
| R-TECH-02 | Critical providers managed without contracts, DPAs or exit plans on record | New | Third-party and AI providers | SUP-S13, SEC-025 | Bhavish Nobeen (RACI row 24: R); Reezvi Pydiah (A) | 4×4 = 16 High | 3×4 = 12 **High** | Mitigate | Open | Yes | Yes |
| R-TECH-03 | Wrong AI-produced values reach bank documents | New | AI output accuracy | SUP-S13, SEC-024 | Software Engineering — Bhavish Nobeen | 4×4 = 16 High | 3×3 = 9 **Medium** | Mitigate | Open | No | No |
| R-TECH-04 | Privacy governance not yet in place | New | Regulatory / privacy | PRIV-004, PRIV-013 | Reezvi Pydiah (A); the DPO | 4×4 = 16 High | 3×4 = 12 **High** | Mitigate | Open | Yes | Yes |
| R-TECH-05 | Cross-border transfer without a recorded legal basis | New | Regulatory / privacy | PRIV-006, SUP-S13 | Reezvi Pydiah (A); the DPO | 3×4 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | Yes |
| R-TECH-06 | Incident response plan is a stub | New | Security | SEC-032, SUP-S11 | Rushal (incident owner, RACI row 11); Reezvi Pydiah (A) | 4×4 = 16 High | 3×4 = 12 **High** | Mitigate | Open | Yes | Yes |
| R-TECH-07 | Staff confidentiality and awareness not evidenced | New | Security | PRIV-017 | Board of Directors | 3×3 = 9 Medium | 3×3 = 9 **Medium** | Mitigate | Open | No | Yes |
| R-TECH-08 | Eleven critical library CVEs unpatched past the 72-hour limit (issue #52) | New | Security | SEC-026, SEC-009 | IT Infrastructure — Bhavish Nobeen | 5×5 = 25 Critical | 4×5 = 20 **Critical** | Mitigate | Open — overdue | Yes | No |
| R-TECH-09 | CI not running and branch protection not enforced | New | Security | SEC-010, SEC-025, SUP-S05, SEC-042, SEC-009 | Software Engineering — Bhavish Nobeen | 5×4 = 20 Critical | 5×4 = 20 **Critical** | Mitigate | Open | Yes | No |
| R-TECH-10 | Audit trail has no tamper evidence against a database superuser | New | Security | SEC-035, SEC-033 | Software Engineering — Bhavish Nobeen | 2×5 = 10 High | 2×4 = 8 **Medium** | Mitigate | Open | No | No |
| R-TECH-11 | Network egress from production not restricted | New | Data loss | PRIV-002, SUP-S13, SEC-001 | Security — Bhavish Nobeen | 3×5 = 15 High | 3×4 = 12 **High** | Mitigate | Open | Yes | Yes |
| R-TECH-12 | Terraform defects not caught by validation (DEF-1 to DEF-13) | New | Hosting / infrastructure | SEC-001, SUP-S11, PRIV-003, PRIV-006, SEC-033, SUP-S06, SUP-S08 | IT Infrastructure — Bhavish Nobeen | 4×4 = 16 High | 4×3 = 12 **High** | Mitigate | Open | Yes | No |
| R-TECH-13 | Pack evidence not yet independently reviewed or approved | New | Security | SEC-041, PRIV-011, SEC-027 | Taj Aundoo (risk and control review, RACI row 19) | 4×4 = 16 High | 4×3 = 12 **High** | Mitigate | Open | Yes | No |
| R-TECH-14 | Conflicting commitments in documents | New | Regulatory / privacy | SEC-005, PRIV-006, SEC-026 | Board of Directors | 4×3 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | No |
| R-TECH-15 | Colleagues see raw personal data on bank-shared records | New | Regulatory / privacy | PRIV-004, SEC-016 | The DPO; Software Engineering — Bhavish Nobeen | 4×3 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | Yes |
| R-TECH-16 | Capacity limits found by the stress test (MO-017 F-01 to F-09) | New | Availability / performance | SEC-008, SEC-002, SEC-003 | Software Engineering — Bhavish Nobeen | 3×4 = 12 High | 3×3 = 9 **Medium** | Mitigate | Open | No | No |

## 6. Risk details by theme

### 6.1 Security

| ID | Cause → consequence | Current controls (proof) | Residual | Action | Action owner | Target date |
|---|---|---|---|---|---|---|
| R-TECH-08 | Spring Boot parent still 3.5.3 (re-checked by A9 in `pom.xml`); no patch release since the 2026-10-12 scan. → Exploitable internet-facing flaws (Tomcat, Spring Security) at go-live; failed due diligence. | Trivy release gate fails on fixable Critical/High (`.github/workflows/release.yml`) but no release can run while CI is down (`docs/compliance/evidence/A5-vulnerability-assessment-report-2026-10.md` V-01). | 20 Critical | Upgrade Spring Boot and libraries, rebuild, re-scan; written acceptance for any CVE without a 6.2.x fix. | Bhavish Nobeen; Reezvi Pydiah (acceptance) | 2026-10-22 (proposed; overdue since 2026-10-15) |
| R-TECH-09 | GitHub Actions starts no job since 2026-10-16 (billing); merges with red checks and no review; one code owner. → No automated test, scan or SBOM gate; unreviewed code on `main`. | Workflows designed and SHA-pinned; local gate stated in merge messages only (`docs/compliance/evidence/A7-github-change-trail.md`). | 20 Critical | Restore billing and runners; re-run CI on `main`; apply the ruleset without bypass; second code owner. | Bhavish Nobeen | 2026-10-22 (CI), 2026-10-30 (ruleset) (proposed) |
| R-SEC-010 | Reviews not reportable; one code owner; branch protection not enforced. → Defects and vulnerabilities reach main and releases. | PR template and CI gates designed; quarter's history: 32 merges by one account, 6 direct pushes, 0 reviews on sampled merges (`docs/compliance/controls/SEC-010.md`, `docs/compliance/evidence/A7-github-change-trail.md`). | 16 High | Apply the ruleset without bypass; second code owner; export approvals quarterly. | Bhavish Nobeen | 2026-10-30 (proposed; workbook 2026-10-24) |
| R-SEC-032 | Alerting set up for daytime only; today no receiver is configured at all. → Late detection; breach grows; notification deadlines missed. | 26 rules over the audit trail; `AlertingGateD1Test`, `AlertRuleCatalogueTest` re-run by A9. No webhook receiver, no alert variables in the ECS task; infra alarms blocked by DEF-2 (`docs/compliance/controls/SEC-032.md`, `docs/compliance/evidence/A8-infra-checks.md`). | 16 High | On-call relay; pass `MODOCUMENT_ALERTS_*` in the ECS task; trip a HIGH rule out of hours with a written record; approve the escalation policy. | Bhavish Nobeen | 2026-10-30 (overdue workbook date 2026-10-16) |
| R-SEC-009 | Assurance testing never commissioned; CI scans stopped on 2026-10-16. → Exploitable weaknesses at go-live; failed due diligence. | Internal VA report 2026-10 (`docs/compliance/evidence/A5-vulnerability-assessment-report-2026-10.md`); gitleaks, Semgrep, npm audit clean; A9 re-ran the Semgrep repo rules (0 findings). No penetration test; 11 Critical / 41 High open (`docs/compliance/controls/SEC-009.md`). | 12 High | Budget decision and external penetration test with retest; monthly VA report. | Reezvi Pydiah (budget); Bhavish Nobeen (remediation) | 2026-10-31 (budget decision) |
| R-SEC-025 | Pipeline bypass; release pipeline never exercised. → Unapproved or broken change in production. | Tag-driven release with production approval and rollback by digest designed; `MigrationPolicyTest` re-run by A9 (58/58). No tag or release ever ran; merges with red checks and no review (`docs/compliance/controls/SEC-025.md`). | 12 High | Restore CI; ruleset without bypass; first tagged release on staging; approve the change procedure. | Bhavish Nobeen | 2026-10-26 |
| R-SEC-026 | OS and database engine patching forgotten; scheduled scans stopped. → Exploitation of a known flaw. | Dependabot, weekly rebuild with Trivy, RDS automatic minor upgrades; patch policy drafted; scheduled scan of 2026-10-19 did not run (billing); no instance refresh (DEF-10) (`docs/compliance/controls/SEC-026.md`, `docs/compliance/evidence/A8-patch-report-2026-10.md`). | 12 High | Approve the patch policy and one critical limit; instance refresh; monthly patch report. | Bhavish Nobeen | 2026-10-22 |
| R-SPOOF-01 | SPF/DKIM/DMARC not configured. → Fake instructions or links to the bank. | Interim: confirm sensitive requests by phone (workbook). DNS state `TO CONFIRM (organisational)`. | 12 High | SPF/DKIM/DMARC (overdue since 2026-10-09); phone confirmation meanwhile. | Bhavish Nobeen (workbook) | 2026-10-30 (proposed) |
| R-SUP-S09 | Unmanaged devices reach production. → Breach of bank data; credential theft. | No MDM, disk-encryption enforcement or EDR; device policy drafted (`docs/compliance/policies/endpoint-device-policy.md`); CI uses OIDC so no cloud keys on laptops (`docs/compliance/controls/SUP-S09.md`). | 12 High | Choose and deploy MDM and EDR; approve the device/BYOD policy. | Taj Aundoo (repo) / Bhavish Nobeen (workbook) — `TO CONFIRM (organisational)` | Before go-live (`TO CONFIRM (organisational)`) |
| R-SUP-S10 | DMARC absent or p=none. → Fraud against the bank in our name. | Not provable from the repository; workbook date 2026-10-09 passed (`docs/compliance/controls/SUP-S10.md`). | 12 High | Confirm or publish SPF, DKIM, DMARC; MTA-STS; registrar lock. | Taj Aundoo (repo) / Bhavish Nobeen (workbook) — `TO CONFIRM (organisational)` | 2026-10-30 (proposed) |
| R-TECH-06 | Pack-09 pending: no severity matrix, on-call schedule, notification clocks or tabletop. → Slow, inconsistent response to the first incident. | Minimum path written (`docs/security/incident-response-stub.md`); post-incident template (`docs/compliance/policies/post-incident-review-template.md`); escalation policy drafted. | 12 High | Write pack-09; run a tabletop before go-live. | Rushal | 2026-11-30 (proposed) |
| R-TECH-13 | All pack documents are drafts; the Pack 07 reviewer is not named; tests were re-performed by agents. → Answers to the bank rest on unverified owner statements; due-diligence finding. | Completeness check `scripts/compliance/check_pack.py`; independent re-performance by A9 (`docs/compliance/mo-034-internal-control-audit-report.md`). | 12 High | Name the Pack 07 reviewer; Rushal re-performs the Bhavish-owned tests; Taj Aundoo review; Board approval. | Taj Aundoo; Rushal; Board of Directors | 2026-11-02 (proposed) |
| R-SUP-S05 | Long-lived cloud keys in CI (workbook). → Malicious code in production. | GitHub OIDC, no static keys, 58/58 actions SHA-pinned. Staging role trusts every ref; no provenance; production trusts a transitional `main` subject while `main` is unprotected (`docs/compliance/controls/SUP-S05.md`). | 10 High | Restrict trust to environments; remove the transitional main subject; provenance attestation; ruleset (R-TECH-09). | Bhavish Nobeen | 2026-11-13 |
| R-SEC-015 | Infrastructure access not routed through a recording path; no database statement audit. → Investigations stall; no evidence for a bank or a regulator. | In-app privileged actions in the append-only audit trail with session and request ids; `AuditAppendOnlyPgIT` re-run by A9 (5/5). CloudTrail and Session Manager in Terraform, not applied; no pgAudit (`docs/compliance/controls/SEC-015.md`). | 9 Medium | Apply CloudTrail and Session Manager; add pgAudit; rebuild one admin session from locked logs. | Bhavish Nobeen | 2026-11-13 |
| R-SEC-033 | Short retention everywhere. → Failed investigation or dispute. | AuditEvent never purged by the app; 7-year Object-Lock archive designed, receives CloudTrail only; app logs 90 days (DEF-5) (`docs/compliance/controls/SEC-033.md`). | 9 Medium | Ship log groups and daily AuditEvent export to the archive; enable locks after the drill; yearly rehydration test. | Bhavish Nobeen | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| R-SEC-040 | Matrices never produced before. → Blind spots. | Access matrix pinned by `RolePermissionMatrixTest` (re-run by A9); threat matrix: 4 of 12 threats have a tested alert (`docs/compliance/controls/SEC-040.md`). | 9 Medium | Detective control or written acceptance for the 8 remaining threats; approve both matrices. | Bhavish Nobeen; Reezvi Pydiah (acceptance) | 2026-11-30 (proposed) |
| R-SUP-S12 | Never set up; the published contact is a placeholder. → Uncoordinated public disclosure. | `frontend/public/.well-known/security.txt` and a disclosure policy exist; contact is on the reserved `.example` domain; no mailbox (`docs/compliance/controls/SUP-S12.md`). | 9 Medium | Create the mailbox to the directors; real address in security.txt; publish the policy; CERT-MU subscription. | Taj Aundoo (mailbox) / Bhavish Nobeen (policy) — split `TO CONFIRM (organisational)` | 2026-10-30 |
| R-TECH-07 | No NDA, confidentiality undertaking (PRIV-021) or security/privacy training record in the repository. → Staff mishandle bank data; no basis to enforce confidentiality. | `TO CONFIRM (organisational)`: NDAs, undertakings and training records. | 9 Medium | Signed undertaking before access; yearly security and privacy training with a record. | Reezvi Pydiah; the DPO (privacy content) | 2026-11-30 (proposed) |
| R-SEC-007 | Object-level checks were inconsistent (workbook). → Data leak at machine speed. | RLS, 404 on foreign objects, rate limits; `CrossUserAccessTest`, `ApiRateLimitIntegrationTest`, `FixedWindowRateLimiterTest` re-run by A9; probe: bank B got 404 on every bank A object. No ZAP API scan; counters per instance (`docs/compliance/controls/SEC-007.md`). | 8 Medium | Authenticated ZAP API scan in staging; shared rate-limit store or WAF decision. | Bhavish Nobeen | 2026-11-06 |
| R-SEC-042 | No SBOM; dependency state unknown per release (workbook). → Slow response to a mass vulnerability. | CycloneDX SBOMs for 1.2.0 committed (`docs/compliance/evidence/A7-sbom-summary.md`); CI SBOM and dependency-review not running (`docs/compliance/controls/SEC-042.md`). | 8 Medium | Restore CI; fix the frontend SBOM; licence gate; Trivy fs scan of the Maven tree. | Bhavish Nobeen | 2026-11-13 |
| R-TECH-10 | No hash chain or verification job. → Altered evidence goes unnoticed. | Tamper drill: a superuser edit succeeded and nothing flagged it (`docs/compliance/evidence/A7-audit-tamper-drill.md`); A9 found no hash-chain code in `src/main`. | 8 Medium | Per-bank SHA-256 hash chain with daily verification and anchoring to the Object-Lock archive; govern the RDS master credential. | Bhavish Nobeen | 2026-12-18 |
| R-PRIV-011 | CIA view never consolidated. → Piecemeal controls leave integrity or availability unmanaged. | One-page CIA table with a proof in every cell; A9 re-ran the reference check (0 missing) (`docs/compliance/controls/PRIV-011.md`, `docs/security/cia-controls-table.md`). Availability column is Terraform only; table not approved. | 6 Medium | Reezvi Pydiah approves the table; restore drill (gate D6) fills the availability column. | Reezvi Pydiah (approval); Rushal (drill) | 2026-11-02 (proposed) |
| R-SEC-006 | OpenAPI spec drifts from implementation. → Integrators mis-integrate; unreviewed routes. | 245-route inventory pinned by `OpenApiContractTest` (re-run by A9, 7/7); the test does not gate merges while CI is down (`docs/compliance/controls/SEC-006.md`). | 6 Medium | Restore CI (R-TECH-09); deploy the sandbox; publish a versioned spec. | Bhavish Nobeen | 2026-11-20 |
| R-SEC-028 | Internal hops and backups not encrypted (workbook). → Interception or copy of bank data. | Encryption at rest and TLS designed; `DatabaseTlsConfigTest`, `HeaderContractTest` re-run by A9. ALB to task plain HTTP inside the VPC; bank DB targets may disable TLS (`docs/compliance/controls/SEC-028.md`). | 6 Medium | TLS to containers or written acceptance; refuse non-TLS bank targets; own CMKs for backups and logs. | Bhavish Nobeen | 2026-11-30 |
| R-SEC-031 | Export built without documentation or tenant-scoping review (workbook). → Bank SOC cannot detect attacks on its data. | Documented ECS schema; bank-scoped pull feed and signed push; `SiemFeedE6Test` re-run by A9 (6/6). No reference-SIEM test (`docs/compliance/controls/SEC-031.md`). | 6 Medium | Reference-SIEM test on staging; structured logs in the ECS task. | Bhavish Nobeen (workbook) / Rushal (roadmap) | 2026-11-30 |
| R-SEC-034 | No correlation ids across services (workbook). → Slow or failed investigation. | One request id across response, logs and audit; `RequestTraceabilityE9Test` re-run by A9. Edge, ALB, nginx and agent do not log the id (`docs/compliance/controls/SEC-034.md`). | 6 Medium | Request id in nginx and agent; ALB/CloudFront/WAF logs; structured logs in ECS. | Bhavish Nobeen | 2026-11-30 |
| R-SEC-041 | Security work previously unplanned. → Same findings every year. | Dated roadmap of 28 items with owners (`docs/security/security-roadmap.md`); no budget amounts, no minutes of the monthly review (`docs/compliance/controls/SEC-041.md`). | 6 Medium | Set budget lines; confirm proposed dates; minute the monthly director review. | Reezvi Pydiah (budget); Bhavish Nobeen (dates) | 2026-10-31 |
| R-SUP-S01 | No CSP; cookies missing flags (workbook). → Account takeover from the browser. | Strict CSP and headers; HttpOnly/Secure/SameSite cookie; `HeaderContractTest`, `UploadContentGateSec030Test` re-run by A9. Access token in localStorage; no ZAP result on file (`docs/compliance/controls/SUP-S01.md`). | 6 Medium | Access token in memory; ZAP report; CSRF regression test. | Bhavish Nobeen | 2026-11-06 |
| R-SUP-S02 | No revocation path (workbook). → Continued unauthorised access. | 15-minute tokens, rotation with reuse detection, revocation; `RevocationValidatorTest`, `JwtServiceTest`, `AuthFlowIntegrationTest` re-run by A9. No iss/aud; one HS256 key (`docs/compliance/controls/SUP-S02.md`). | 6 Medium | iss/aud claims; asymmetric keys with kid. | Bhavish Nobeen | 2026-11-20 |
| R-SEC-018 | Secrets in committed environment files (workbook). → Production compromise. | gitleaks history clean (`docs/compliance/evidence/A5-scans-and-tests.md`); Secrets Manager shells in Terraform; `SecretFileEnvironmentPostProcessorTest` re-run by A9. Secret scanning not running in CI since 2026-10-16; Argon2id below baseline (`docs/compliance/controls/SEC-018.md`). | 5 Medium | Restore CI scanning (R-TECH-09); raise Argon2id parameters; rotation or IAM DB auth. | Bhavish Nobeen | 2026-11-06 |
| R-SEC-031b | Tenant scoping of the feed was unknown (workbook). → Cross-tenant leak through security tooling. | Feed takes the bank from the token and RLS; two-bank test `SiemFeedE6Test` passes (re-run by A9). No bank SIEM connected (`docs/compliance/controls/SEC-031.md`). | 5 Medium | Keep the avoidance: connect no bank SIEM until the scoping proof is accepted by the bank. | Bhavish Nobeen | Ongoing |
| R-SEC-019 | Request logger captures login payloads (workbook). → Credential exposure through logs. | Redaction on every log path; `LogRedactionCanaryTest` and `RedactingConverterTest` re-run by A9 (`docs/compliance/controls/SEC-019.md`). | 4 Low | 30-day canary search after go-live. | Bhavish Nobeen | 2026-11-20 |
| R-SEC-035 | Audit table updatable by the app account (workbook). → Undetected manipulation of evidence. | UPDATE/DELETE/TRUNCATE revoked and blocked by triggers; `AuditAppendOnlyPgIT` re-run by A9 on PostgreSQL (5/5). Superuser path: see R-TECH-10 (`docs/compliance/controls/SEC-035.md`). | 4 Low | Make `ExtractionAuditEvent` append-only. | Bhavish Nobeen | 2026-12-18 |
| R-SEC-029 | No written crypto standard (workbook). → Weak protection of secrets. | Crypto standard written; Semgrep rules against weak algorithms re-run by A9 (0 findings); `MfaSecretCipherTest`, `JwtServiceTest` re-run. Rule not enforced while CI is down (`docs/compliance/controls/SEC-029.md`). | 3 Low | Restore CI; explicit KMS key policies; approve the standard. | Bhavish Nobeen | 2026-11-30 |
| R-SEC-027 | Knowledge in engineers' heads. → Lost assessment credit. | Security feature catalogue; A9 re-ran the reference check (106 paths, 100 test classes, 0 missing) (`docs/compliance/controls/SEC-027.md`). | 2 Low | Human approval; add the reference check to CI. | Bhavish Nobeen | 2026-11-02 |

### 6.2 Access

| ID | Cause → consequence | Current controls (proof) | Residual | Action | Action owner | Target date |
|---|---|---|---|---|---|---|
| R-SEC-017 | Small founding team; admin rights on everyday identities; no grant record. → Misuse of privileged access goes unseen; no accountability. | In product: PLATFORM_ADMIN only in the platform bank, two-person rule, MFA, alerts; probe confirmed refusals (`docs/compliance/evidence/A4-rbac-probe.md`). Outside product: no separate admin identities, no grant record, no monthly review (`docs/compliance/controls/SEC-017.md`); break-glass temporary password chosen by the platform maker (MO-019 X-04). | 12 High | Grant record approved by Reezvi Pydiah; separate admin identities; first monthly review; add PLATFORM_ADMIN to the admin-login alert. | Bhavish Nobeen; Reezvi Pydiah (grant record) | 2026-10-30 |
| R-SHARED-01 | Pre-production setup. → No attribution; no individual removal. | Synthetic data only; individual logins built and tested (`docs/compliance/evidence/A4-rbac-probe.md`). Deletion of the shared account: `TO CONFIRM (organisational)`. | 9 Medium | Delete the account on 2026-10-21 and record it. | Bhavish Nobeen | 2026-10-21 |
| R-PRIV-017 | Leavers disabled in the IdP while other credentials survive. → Unauthorised access to bank data. | Leaver approval kills token and login at once (probe); `UserAdminIntegrationTest` re-run by A9 (10/10). No quarterly review held; shared team account to delete (`docs/compliance/controls/PRIV-017.md`). | 8 Medium | Delete the shared account; ticket per joiner; first quarterly review at go-live. | Bhavish Nobeen | 2026-10-21 |
| R-SEC-014 | MFA optional for some privileged paths. → Full bank-tenant takeover. | TOTP forced for ADMIN and PLATFORM_ADMIN; `MfaFlowIntegrationTest`, `MfaUseCaseTest` re-run by A9. No step-up; OIDC sign-in does not check amr/acr; APPROVER MFA optional (MO-019 X-02); compose default admin MFA off (`docs/compliance/controls/SEC-014.md`). | 8 Medium | Step-up TOTP on approve/role/export; require amr/acr for ADMIN OIDC; MFA for APPROVER. | Bhavish Nobeen | 2026-11-20 |
| R-SEC-013 | No formal recertification cycle yet; no time-bound elevation. → Wide data exposure from one account. | Granular permissions (READ_ONLY 9 of 41), owner scoping, RLS, export caps; `TenantIsolationGatePgIT` and `RlsPolicyCoveragePgIT` re-run by A9 on PostgreSQL (`docs/compliance/controls/SEC-013.md`). No recertification held. | 6 Medium | First quarterly recertification at go-live; time-limited elevation; IAM policy lint in CI. | Bhavish Nobeen | 2026-11-30 |
| R-SEC-020 | Support could see or set known passwords (workbook). → Fraudulent actions in a user's name. | Hash-only credentials; resets are maker/checker; `UserAdminUseCaseTest` re-run by A9. Temporary password known to the requesting admin and never expires (`docs/compliance/controls/SEC-020.md`). | 6 Medium | Expire temporary passwords; dumped-table sign-in test. | Bhavish Nobeen | 2026-11-06 |
| R-SEC-021 | Length rule only; breached passwords accepted (workbook). → Account takeover. | 12-character minimum, 11,384-entry breached list, lockout; `PasswordPolicyTest`, `AccountLockoutIntegrationTest` re-run by A9 (`docs/compliance/controls/SEC-021.md`). | 6 Medium | Larger breached list or k-anonymity lookup; Keycloak blacklist. | Bhavish Nobeen | 2026-11-20 |
| R-SEC-022 | Reset credentials never expire. → Account takeover. | TOTP replay refused, short token lifetimes; `TotpTest`, `AuthFlowIntegrationTest` re-run by A9. Admin-issued temporary passwords never expire (`docs/compliance/controls/SEC-022.md`). | 6 Medium | 72-hour expiry of unused temporary passwords. | Bhavish Nobeen | 2026-11-06 |
| R-SEC-011 | Routes without an explicit permission. → Privilege creep; open data routes. | Deny by default; `RoutePermissionCoverageTest` and `RolePermissionMatrixTest` re-run by A9; probe 352/352 re-checked by A9 against the CSV (`docs/compliance/controls/SEC-011.md`). | 4 Low | Keep the coverage test as a required check once CI runs; quarterly matrix review. | Bhavish Nobeen | 2026-10-21 |
| R-SEC-016 | Report engine outside the authorisation layer (workbook). → Data leak via reports. | Every report is an authorised, tenant-scoped route; masking by role; `AdminDashboardIsolationTest`, `MaskingGateC2Test` re-run by A9 (`docs/compliance/controls/SEC-016.md`). | 4 Low | Privileged-activity view on the dashboards. | Bhavish Nobeen | 2026-11-30 |
| R-SEC-023 | Maker could approve own change (workbook). → Unauthorised critical change. | 17 change types under maker/checker; self-approval 409; `MakerCheckerIntegrationTest`, `PendingChangeAudienceTest` re-run by A9 (`docs/compliance/controls/SEC-023.md`). | 4 Low | Correct `docs/security/maker-checker.md` (stale onboarding text). | Bhavish Nobeen | 2026-11-14 |
| R-SEC-012 | Only user/admin roles (workbook). → Excess privilege. | Six roles incl. APPROVER; one role per user; `SodPolicyTest`, `RoleAssignmentPolicyTest` re-run by A9 (`docs/compliance/controls/SEC-012.md`). | 3 Low | Annual role catalogue review. | Bhavish Nobeen | 2026-10-21 |
| R-SEC-024 | SoD documented but not scanned (workbook). → Fraud or error through role drift. | Single role per user; runtime SoD; nightly scan; `DocReleaseSodIntegrationTest`, `SodPolicyTest` re-run by A9 (`docs/compliance/controls/SEC-024.md`). | 3 Low | Keep monthly SOD_SCAN_COMPLETED evidence after go-live; DPO decision on DSR dual control. | Bhavish Nobeen; the DPO | After go-live |

### 6.3 Data loss

| ID | Cause → consequence | Current controls (proof) | Residual | Action | Action owner | Target date |
|---|---|---|---|---|---|---|
| R-PRIV-006 | Restores never timed end to end. → Extended outage and data loss. | Backup design and restore procedures R1–R5 written (`docs/compliance/policies/storage-backup-recovery-map.md`); never run; RTO 2 h vs 4 h unresolved (`docs/compliance/controls/PRIV-006.md`). | 15 High | Timed restore drill (gate D6) in staging and production; then quarterly; agree one RTO. | Rushal (drill); Bhavish Nobeen (DR lead) | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| R-TECH-11 | `app_egress_https_cidrs` defaults to 0.0.0.0/0; `enforce_egress_nacl` false (re-checked by A9). → Data exfiltration to any internet host; AI calls to an unapproved endpoint. | Application-level controls only: `OutboundHostPolicy` and approved bank egress rules (`docs/compliance/controls/PRIV-002.md`). DEF-8 blocks the NACL as written. | 12 High | Sign off the allow-list (gate D3); narrow the CIDRs; fix DEF-8; alert on new destinations. | Bhavish Nobeen (define); Reezvi Pydiah (sign-off) | 2026-11-13 |
| R-PRIV-003 | Backups deletable with production credentials. → Total service and data loss. | Vault lock and cross-region copy in Terraform but locks off until the drill; file store not backed up (DEF-4); GuardDuty runtime/malware missing (DEF-3); A9 re-ran `terraform validate` (`docs/compliance/controls/PRIV-003.md`). | 10 High | Restore drill, then `enforce_backup_locks = true`; lock account; file-store copy; GuardDuty features. | Bhavish Nobeen; Rushal (drill) | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| R-PRIV-002 | Workbook: export limits never built. Today: caps are built; network egress is not restricted and alerts reach no person. → Bulk data theft; regulatory notification deadlines missed because the event is not seen. | Per-role export caps with reason above the cap, audited and alerted; `ExportCapGateD2Test` re-run by A9 (5/5) (`docs/compliance/controls/PRIV-002.md`). No phone path for alerts (`docs/compliance/controls/SEC-032.md`); egress open (R-TECH-11). | 8 Medium | Connect the alert phone path (SEC-032); add `AUDIT_LOG_EXPORTED` to an alert rule; egress allow-list under R-TECH-11. | Bhavish Nobeen | 2026-11-13 |

### 6.4 Availability / performance

| ID | Cause → consequence | Current controls (proof) | Residual | Action | Action owner | Target date |
|---|---|---|---|---|---|---|
| R-SEC-003 | Autoscaling configured but never observed working. → Slow or failed service at peaks; cost. | CPU target tracking 2–8 tasks in Terraform, not applied; stress test: CPU saturates first (one CPU carried 2× peak at 73 % mean CPU), so CPU is the right signal; memory headroom 160 MiB at 2,656 MiB RSS (F-04) (`docs/compliance/controls/SEC-003.md`). | 9 Medium | Fix the memory headroom (F-04); apply staging and record a scale-out event with zero 5xx under MO-016 load. | Bhavish Nobeen; Rushal (run) | 2026-11-30 (F-04); 2027-01-31 (scale-out evidence) |
| R-SEC-005 | SLA quoted with no external probe or report. → Service-credit disputes; loss of trust. | Probe endpoint built; `SyntheticProbeTest` re-run by A9 (3/3). No external monitor, status page or signed clause (`docs/compliance/controls/SEC-005.md`). | 9 Medium | External probe with alarm; status page; agreed SLA clause; monthly uptime report. | Bhavish Nobeen; Rushal | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| R-SUP-S11 | No external synthetic probing. → Late response; loss of trust. | Probe endpoint and Terraform alarms; alarm delivery blocked by DEF-2; no dashboards or paging (`docs/compliance/controls/SUP-S11.md`). | 9 Medium | Synthetics canaries, dashboard, burn-rate alarms, paging; fix DEF-2. | Bhavish Nobeen; Rushal | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| R-TECH-16 | Full 50,000-row job read of 17.9 MB / 765 ms (F-01); 2 ingestion threads with an unbounded queue (F-02); 35 s re-index holding a DB connection (F-03); RSS 160 MiB under the container limit (F-04); synchronous generation (F-05); no early shedding (F-06). → Slow screens with large jobs, queued uploads, a container killed for memory under heavy generation. | No error, 5xx or failed job in any run (`docs/compliance/mo-017-stress-testing-report.md` §7, raw output `perf/results/stress-2026-10/`, re-computed by A9); each finding has an owner and a proposed date. | 9 Medium | F-04 memory headroom; F-01 paged job rows; F-02/F-05 bounded queues; F-03 batched re-index; F-06 to F-08 later; re-test on staging. | Bhavish Nobeen | 2026-11-30 (F-04); 2026-12-15 (F-01–F-03, F-05) (proposed) |
| R-SEC-002 | Workbook: no rate limiting. Today: limits exist; report generation has no queue and the ingestion queue is unbounded. → Outage and SLA breach. | Every limit answered 429 with `Retry-After` at its limit and other users were unaffected; AI outage: 20/20 jobs completed; full DB pool: waiting, 0 errors (MO-017 S5; A9 counted the 22 × 429 in the raw output). Generation synchronous (F-05), ingestion queue unbounded (F-02), counters per instance (F-07); WAF not applied (`docs/compliance/controls/SEC-002.md`). | 8 Medium | Bounded queues for generation and ingestion (429 or 202 when full); shared rate-limit store; apply the WAF. | Bhavish Nobeen | 2026-12-15 |
| R-SEC-004 | Single-AZ database (workbook). → Client-visible outage. | Production Terraform: RDS Multi-AZ, two-AZ app tier, minimum 2 tasks; never failed over (`docs/compliance/controls/SEC-004.md`). | 8 Medium | Zone-failure and forced-failover test in staging, then production. | Bhavish Nobeen; Rushal | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| R-SEC-008 | Workbook: no test ever run. Today: tested on a shared test box only; expected peak not stated. → Outage or slow service at the first peak. | MO-016 plan run on 2026-10-19 (`docs/compliance/mo-017-stress-testing-report.md`): 0 errors at 1×, 2× and a 4× spike of an assumed 50-user peak; breakpoint 400 users on 4 shared CPUs, 150 on one CPU; A9 re-computed the p95 figures from the raw output in `perf/results/stress-2026-10/` (all match). Not run on staging; expected peak `TO CONFIRM (organisational)`. | 8 Medium | Business states the expected peak; re-run MO-016 on staging at 2× that peak with a 1-hour soak (gate E7) and re-issue MO-017. | Bhavish Nobeen (plan, report); Rushal (staging run) | Before go-live (workbook control date 2027-01-31) |
| R-SUP-S03 | Origin reachable directly (workbook). → Outage in business hours. | CloudFront + WAF, origin lock and rate rules in Terraform; staging has no WAF; mTLS listener bypasses the WAF (`docs/compliance/controls/SUP-S03.md`, `docs/compliance/policies/ddos-runbook.md`). | 8 Medium | Deploy the edge; origin-bypass and flood tests; edge logs; Shield Advanced decision. | Bhavish Nobeen | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |

### 6.5 Hosting / infrastructure

| ID | Cause → consequence | Current controls (proof) | Residual | Action | Action owner | Target date |
|---|---|---|---|---|---|---|
| R-TECH-01 | Nothing in `infra/terraform/` has been applied; no staging account; `plan` never run. → First deployment fails or starts with controls inert; go-live slips. | `terraform fmt` and `validate` pass (re-run by A9 on 2026-10-19, `docs/compliance/evidence/A9-retest-runs.md`); validate cannot catch runtime defects (`docs/compliance/evidence/A8-infra-checks.md`). | 12 High | Create the AWS accounts; apply staging first; run plan, drills and the go-live gate tests in staging. | Bhavish Nobeen | 2026-11-30 (proposed) |
| R-TECH-12 | No IaC scanner or plan in CI; defects found only by reading. → Tasks cannot start (DEF-1); alarms and GuardDuty findings not delivered (DEF-2); file store unrecoverable (DEF-4); logs not archived (DEF-5). | 13 defects listed with fixes (`docs/compliance/evidence/A8-infra-checks.md` §4); none fixed (A9 re-checked DEF-1, DEF-2 in code). | 12 High | Fix DEF-1 and DEF-2 first; add tfsec/Checkov and a plan job to CI. | Bhavish Nobeen | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| R-SUP-S08 | No register; expiries found by outage. → Outage; unrecoverable encrypted data. | 24-row key and certificate register; rotation tests `ConnectorSecretVaultTest`, `IntegrationApiSecurityTest` re-run by A9. No expiry alerts; no key escrow for MFA and connector keys (`docs/compliance/controls/SUP-S08.md`). | 9 Medium | Expiry alerts at 30/14/7 days; key escrow; staging rotation drill. | Bhavish Nobeen | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| R-SUP-S06 | App account owns DDL and the schema (workbook). → Bypass of RLS and audit. | Private subnets, forced TLS, DML-only NOBYPASSRLS runtime role (`src/main/resources/db/migration/V12__app_role_grants.sql`); the app task also holds the master credentials (DEF-11) (`docs/compliance/controls/SUP-S06.md`). | 8 Medium | Run Flyway outside the service task; personal audited DB accounts; negative connection tests. | Bhavish Nobeen | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| R-SUP-S07 | Shared cloud account across environments (workbook). → Production data exposed through staging. | Separate accounts, state and VPCs designed; synthetic test data policy. No account exists; no `allowed_account_ids`; replay tests overdue (`docs/compliance/controls/SUP-S07.md`). | 8 Medium | Create the accounts; pin account ids; run the replay and reachability tests. | Bhavish Nobeen | 2026-11-30 |
| R-SEC-001 | Diagram out of date versus reality. → Unprotected paths go unnoticed. | Architecture with every box mapped to Terraform (`docs/compliance/policies/security-architecture.md`); DNS, ACM and GPU servers outside IaC; DEF-1 (`docs/compliance/controls/SEC-001.md`). | 6 Medium | Fix DEF-1; bring DNS, certificates and model servers into IaC or scope them out; drift-free plan. | Bhavish Nobeen | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |
| R-REGION-01 | Cost decision: no warm standby. → Long recovery from the cross-region copy. | Daily database copy to ap-southeast-2 in Terraform; region-loss RTO not documented; file store not replicated (`docs/compliance/controls/SEC-004.md`). | 5 Medium | Document the region-loss procedure and RTO in the DR plan; test once a year. | Bhavish Nobeen; Reezvi Pydiah (acceptance) | 2026-11-30 (proposed) |
| R-SUP-S04 | Images as root, no policies (workbook). → Lateral movement. | No Kubernetes; non-root images, dropped capabilities, no-new-privileges, IMDSv2 (`docs/compliance/controls/SUP-S04.md`). No image signing; writable root filesystems. | 4 Low | Image signing and verification; read-only root filesystems; digest-pinned bases. | Bhavish Nobeen | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) |

### 6.6 Third-party and AI providers

| ID | Cause → consequence | Current controls (proof) | Residual | Action | Action owner | Target date |
|---|---|---|---|---|---|---|
| R-TECH-02 | No vendor register; AWS, GitHub, identity providers, model provider and DNS/e-mail terms are not in the repository. → Unplanned outage (as with the GitHub Actions billing stop), data-processing terms missing, no exit route. | GitHub Actions stopped by a billing failure (`docs/compliance/evidence/A8-patch-report-2026-10.md`); production model provider undecided (`docs/compliance/controls/SUP-S13.md`). Contracts and DPAs: `TO CONFIRM (organisational)`. | 12 High | Vendor register with owner, contract, DPA, data location, exit plan; billing alerts on GitHub and AWS. | Bhavish Nobeen; the DPO (DPAs) | 2026-11-30 (proposed) |
| R-SUP-S13 | Clients calling an AI API with raw data (workbook). → Unlawful transfer; disclosure of one bank's data to another. | All calls through `AiGateway` with tokenisation, one-bank guard and output scan; local model by default; `AiGatewayC9Test` and `AiResultCacheRlsPgIT` re-run by A9. Production endpoint undecided (`docs/compliance/controls/SUP-S13.md`). | 8 Medium | Decide the production model location; if external, provider terms and DPA, transfer assessment, egress allow-list entry. | Bhavish Nobeen; the DPO (transfer) | 2026-11-02 |

### 6.7 AI output accuracy

| ID | Cause → consequence | Current controls (proof) | Residual | Action | Action owner | Target date |
|---|---|---|---|---|---|---|
| R-AI-01 | Design decision 2 Oct 2026. → Wrong figures or manipulated text in bank documents. | Text-only output, pseudonymisation, output scanning, human review before release; four residual risks listed in `docs/security/ai-controls.md`; C11 acceptance not signed. | 12 High | Reezvi Pydiah signs the C11 acceptance of the four residual risks. | Reezvi Pydiah | 2026-11-02 |
| R-TECH-03 | Model-written figures, OCR and auto-mapping errors; no accuracy measurement per release; no model pinning. → Incorrect regulatory or customer documents issued by a bank. | Validation rules re-run on edited tables, output scanning, four-eyes document release (`docs/security/ai-controls.md`; `DocReleaseSodIntegrationTest` re-run by A9). No accuracy benchmark exists in the repository. | 9 Medium | Golden test set for extraction and generation with an accuracy threshold per release; pin model versions. | Bhavish Nobeen | 2026-12-18 (proposed) |

### 6.8 Key-person dependency

| ID | Cause → consequence | Current controls (proof) | Residual | Action | Action owner | Target date |
|---|---|---|---|---|---|---|
| R-PEOPLE-02 | Management decision 2 Oct 2026 to assign all fixes to Bhavish Nobeen. → Slipped dates across all three workbooks; single point of failure for security, DR and privacy work. | Rushal named deputy per item; weekly status check; re-plan if two dates slip (workbook). The pack shows 7 workbook dates already passed (`docs/security/security-roadmap.md`). | 16 High | Assign a second named owner for at least the Day 30 items; Board reviews the load quarterly (MO-032). | Board of Directors; Reezvi Pydiah | 2026-11-02 (proposed) |
| R-SCHED-01 | Single-owner plan. → Missed client commitments. | 21 Oct internal target; buffered date for MauBank; Rushal deputy (workbook). Several items are already re-proposed (`docs/security/security-roadmap.md`). | 12 High | Re-plan with buffered external dates; weekly status check. | Bhavish Nobeen; Rushal | 2026-10-21 |

### 6.9 Regulatory / privacy

| ID | Cause → consequence | Current controls (proof) | Residual | Action | Action owner | Target date |
|---|---|---|---|---|---|---|
| R-TECH-04 | DPO identity and reporting line unconfirmed (MO-038); DPIA unsigned; breach-notification path names no DPO step; controller/processor split unconfirmed. → Late or wrong breach notification; regulatory finding; bank due-diligence failure. | DPIA draft (`docs/compliance/policies/dpia-modocument.md`); incident stub without a DPO step (`docs/security/incident-response-stub.md`; MO-001 F1, F2). | 12 High | Confirm the DPO (MO-038); DPO signs the DPIA; add the DPO step to breach notification; record the controller/processor position. | Board of Directors; the DPO | 2026-11-14 (proposed) |
| R-TECH-05 | Daily database copy to ap-southeast-2 (Sydney); a possible external model provider. → Unlawful transfer of bank customer data. | Copy region set in `infra/terraform/envs/production/providers.tf`; legal basis `TO CONFIRM (organisational)` (`docs/compliance/controls/PRIV-006.md`). | 9 Medium | DPO records the transfer basis or the region is changed before go-live. | The DPO; Bhavish Nobeen | Before go-live (`TO CONFIRM (organisational)`) |
| R-TECH-14 | RTO 2 h (workbook) vs 4 h (`docs/security/sla-and-status.md`); critical patch 24 h vs 72 h; owner conflicts (MO-001 §3.4). → Contract or questionnaire answer that cannot be met. | Conflicts listed (`docs/compliance/evidence/A8-infra-checks.md` DOC-7; `docs/compliance/mo-001-governance-structure.md` §3.4). | 9 Medium | Board fixes one RTO, one patch limit and the owners when it approves MO-001. | Board of Directors | 2026-11-02 (proposed) |
| R-TECH-15 | SCREEN_WORKING raw view extends to records of bank-shared templates (MO-019 X-03). → Wider internal access to personal data than the purpose needs. | Masking policy (`docs/security/masking-matrix.md`); probe evidence (`docs/compliance/evidence/A4-rbac-probe.md` scenarios 28–35). | 9 Medium | DPO decision: mask colleagues' records or accept with a documented purpose. | The DPO; Bhavish Nobeen | 2026-11-14 (proposed) |
| R-PRIV-013 | Erasure does not reach every store. → Complaint to the Data Protection Commissioner. | Audited DSR search/export/erase; `DataSubjectRightsGateC12Test` and `RetentionPurgeGateC5Test` re-run by A9. Document titles, PDF photos and the scale-build Mongo store not reached (`docs/compliance/controls/PRIV-013.md`). | 8 Medium | Extend erase to titles and photos; cover or forbid the Mongo store; confirm the statutory period with the DPO. | Bhavish Nobeen; the DPO | 2026-11-20 |
| R-PRIV-004 | No systematic PII tagging. → Larger breach, DSR and retention exposure. | PII tags, masking engine, tokenisation before AI, 30-day retention; `MaskingMatrixDocTest`, `PiiTokenizerTest`, `MaskingGateC2Test` re-run by A9. DPIA drafted, not signed by the DPO (`docs/compliance/controls/PRIV-004.md`). | 6 Medium | DPO sign-off of the DPIA; API-wide canary PII test; update the data inventory. | The DPO; Bhavish Nobeen | 2026-11-20 |

## 7. Checklist themes and where they are covered

MO-031 asks for nine themes. Each is covered by workbook risks; a new `R-TECH` risk is added where the workbooks leave a gap.

| Checklist theme | Risks |
|---|---|
| Hosting / infrastructure | R-SUP-S07, R-SEC-001, R-REGION-01, R-SUP-S04, R-SUP-S06, R-SUP-S08, R-TECH-01, R-TECH-12 |
| Third-party and AI providers | R-SUP-S13, R-TECH-02 |
| Security | R-PRIV-011, R-SEC-009, R-SEC-015, R-SEC-031, R-SEC-031b, R-SEC-032, R-SEC-040, R-SEC-041, R-SUP-S12, R-SEC-006, R-SEC-007, R-SEC-010, R-SEC-018, R-SEC-019, R-SEC-025, R-SEC-027, R-SEC-028, R-SEC-029, R-SEC-034, R-SEC-035, R-SEC-042, R-SUP-S01, R-SUP-S02, R-SUP-S05, R-SEC-026, R-SEC-033, R-SUP-S09, R-SUP-S10, R-SPOOF-01, R-TECH-06, R-TECH-07, R-TECH-08, R-TECH-09, R-TECH-10, R-TECH-13 |
| Access | R-SEC-013, R-SEC-017, R-PRIV-017, R-SEC-011, R-SEC-012, R-SEC-014, R-SEC-016, R-SEC-020, R-SEC-021, R-SEC-022, R-SEC-023, R-SEC-024, R-SHARED-01 |
| Data loss | R-PRIV-002, R-PRIV-003, R-PRIV-006, R-TECH-11 |
| Availability / performance (stress test, MO-017) | R-SEC-008, R-SEC-002, R-SEC-003, R-SEC-004, R-SEC-005, R-SUP-S03, R-SUP-S11, R-TECH-16 |
| AI output accuracy | R-AI-01, R-TECH-03 |
| Key-person dependency | R-PEOPLE-02, R-SCHED-01 |
| Regulatory / privacy | R-PRIV-004, R-PRIV-013, R-TECH-04, R-TECH-05, R-TECH-14, R-TECH-15 |

## 8. Risks raised by the pack's findings

| Finding | Where found | Register entry |
|---|---|---|
| 11 critical library CVEs unpatched past the 72-hour limit (issue #52) | `docs/compliance/evidence/A5-vulnerability-assessment-report-2026-10.md`, `docs/compliance/evidence/A8-patch-report-2026-10.md` | R-TECH-08 (new); also raises R-SEC-026, R-SEC-009 |
| GitHub Actions starts no job since 2026-10-16; merges with red checks and no review; one code owner | `docs/compliance/evidence/A7-github-change-trail.md`; `docs/compliance/policies/code-review-report-2026-Q4.md` | R-TECH-09 (new); also raises R-SEC-010, R-SEC-025, R-SUP-S05 |
| No hash chain: a superuser edit of an audit row goes undetected | `docs/compliance/evidence/A7-audit-tamper-drill.md` | R-TECH-10 (new) |
| Network egress open to 0.0.0.0/0; egress NACL off and defective (DEF-8) | `docs/compliance/evidence/A8-infra-checks.md` TF-16, TF-86 | R-TECH-11 (new) |
| Alerts reach no person: no receiver, no alert variables in the ECS task; infra alarms blocked by DEF-2 | `docs/compliance/controls/SEC-032.md`; `docs/compliance/evidence/A8-infra-checks.md` | Rated in R-SEC-032 (residual High); DEF-2 also in R-TECH-12 |
| Terraform defects DEF-1 to DEF-13 | `docs/compliance/evidence/A8-infra-checks.md` §4 | R-TECH-12 (new); DEF-4 in R-PRIV-003/R-PRIV-006, DEF-5 in R-SEC-033, DEF-11 in R-SUP-S06 |
| RBAC exceptions X-02 (APPROVER MFA), X-03 (raw colleague records), X-04 (break-glass password) | `docs/compliance/mo-019-role-based-access-system-report.md` §8 | R-SEC-014, R-TECH-15 (new), R-SEC-017 |
| Owner, RTO and patch-limit conflicts | `docs/compliance/mo-001-governance-structure.md` §3.4; A8 DOC-7 | R-TECH-14 (new) |
| Incident response plan is a stub (pack-09 pending) | `docs/security/incident-response-stub.md` | R-TECH-06 (new) |
| Stress-test findings | `docs/compliance/mo-017-stress-testing-report.md` | R-SEC-008, R-SEC-002, R-SEC-003, R-TECH-16 |

## 9. Acceptance register

These risks need a written decision. Reezvi Pydiah decides (approval matrix D11). High and Critical risks are also reported to the Board (proposed — confirm). No acceptance is recorded in the repository; where the workbook records one, it says so.

| ID | Residual | Treatment | Decision needed | Decider | Recorded acceptance | Workbook note |
|---|---|---|---|---|---|---|
| R-TECH-08 | 20 Critical | Mitigate | Treat before go-live: a Critical residual is outside the proposed appetite (MO-032 §5) | Board of Directors | None in the repository | — |
| R-TECH-09 | 20 Critical | Mitigate | Treat before go-live: a Critical residual is outside the proposed appetite (MO-032 §5) | Board of Directors | None in the repository | — |
| R-PEOPLE-02 | 16 High | Accept + mitigate | Accept the residual risk in writing | Reezvi Pydiah; Board (High, proposed) | Accepted (management decision) | Recorded by management on 2 Oct 2026; re-confirm at Board level because the residual is High. |
| R-SEC-010 | 16 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-SEC-032 | 16 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-PRIV-006 | 15 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-AI-01 | 12 High | Accept | Accept the residual risk in writing | Reezvi Pydiah; Board (High, proposed) | None in the repository | Workbook: Reezvi Pydiah (gate C11). |
| R-SCHED-01 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | Workbook: "Recorded". |
| R-SEC-009 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-SEC-017 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-SEC-025 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-SEC-026 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-SPOOF-01 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | Workbook: "No"; the residual is High, so this register asks for an acceptance until DMARC is live. |
| R-SUP-S09 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-SUP-S10 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-TECH-01 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-TECH-02 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-TECH-04 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-TECH-06 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-TECH-11 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-TECH-12 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-TECH-13 | 12 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-PRIV-003 | 10 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-SUP-S05 | 10 High | Mitigate | Approve the treatment plan and accept the residual until it closes, or hold go-live | Reezvi Pydiah; Board (High, proposed) | None in the repository | — |
| R-SHARED-01 | 9 Medium | Accept (interim) | Accept the residual risk in writing | Reezvi Pydiah | Accepted (interim, to 21 Oct) | Workbook: Reezvi Pydiah. The interim acceptance ends on 2026-10-21. |
| R-REGION-01 | 5 Medium | Accept | Accept the residual risk in writing | Reezvi Pydiah | None in the repository | Workbook: Reezvi Pydiah. |

## 10. Privacy and data-protection risks (for the DPO, MO-048)

The checklist asks the DPO to supply the privacy risks. The DPO's input has not been received: `TO CONFIRM (organisational)`. The risks below are drafted from the repository for the DPO to confirm, re-rate or extend at the workshop.

| ID | Risk | Residual | DPO question |
|---|---|---|---|
| R-PRIV-002 | Bulk data theft proceeds unnoticed | 8 Medium | Is the export cap (5,000 rows for READ_WRITE) proportionate? |
| R-PRIV-004 | Unnecessary personal data accumulates | 6 Medium | Sign-off of the DPIA (`docs/compliance/policies/dpia-modocument.md`). |
| R-PRIV-013 | An unanswered subject request becomes a complaint naming the bank | 8 Medium | Statutory DSR response period; bank-side restriction and objection procedure. |
| R-PRIV-017 | Ex-staff or over-privileged accounts keep access | 8 Medium | Is the leaver timing acceptable for personal-data access? |
| R-SUP-S13 | Bank data leaves jurisdiction into a model provider; cross-tenant leakage through model output | 8 Medium | Basis for any external model provider; DPA terms. |
| R-TECH-02 | Critical providers managed without contracts, DPAs or exit plans on record | 12 High | Which providers process personal data, and are DPAs in place? |
| R-TECH-04 | Privacy governance not yet in place | 12 High | DPO appointment and reporting line (MO-038); DPO step in breach notification. |
| R-TECH-05 | Cross-border transfer without a recorded legal basis | 9 Medium | Legal basis for the ap-southeast-2 backup copy. |
| R-TECH-06 | Incident response plan is a stub | 12 High | Breach assessment and notification clock in pack-09. |
| R-TECH-07 | Staff confidentiality and awareness not evidenced | 9 Medium | Privacy content of staff training and the confidentiality undertaking. |
| R-TECH-11 | Network egress from production not restricted | 12 High | Which outbound destinations may receive personal data? |
| R-TECH-15 | Colleagues see raw personal data on bank-shared records | 9 Medium | Mask colleagues' records, or accept with a documented purpose (MO-019 X-03); dual control for DSR export and erasure (X-06). |

## 11. Overdue and passed workbook dates

Workbook dates that have passed on 2026-10-19 are shown in §6 with a new date marked proposed. The main ones:

| Risk | Workbook date | Position on 2026-10-19 |
|---|---|---|
| R-TECH-08 / R-SEC-026 | 72 hours after 2026-10-12 (2026-10-15) | Overdue; proposed 2026-10-22 |
| R-SEC-032 | 2026-10-16 | Overdue; proposed 2026-10-30 |
| R-SEC-010 | 2026-10-16 (roadmap R11) | Overdue; proposed 2026-10-30 |
| R-SPOOF-01 / R-SUP-S10 | 2026-10-09 | Overdue; proposed 2026-10-30 |
| R-SUP-S07 | 2026-10-09 (replay tests) | Overdue; with the first `terraform apply` |
| R-SHARED-01 | 2026-10-21 | Due in two days; deletion not yet evidenced |

## 12. Review and approval

| Role | Name | Decision | Date |
|---|---|---|---|
| Responsible | Bhavish Nobeen | `TO CONFIRM (organisational)` | |
| Risk and control review (A) | Taj Aundoo | `TO CONFIRM (organisational)` | |
| Privacy risks (MO-048) | The DPO | `TO CONFIRM (organisational)` | |
| Risk acceptances (D11) | Reezvi Pydiah | `TO CONFIRM (organisational)` (§9) | |

## 13. To confirm

- `TO CONFIRM (organisational)`: date and attendees of the joint workshop with all directors and the DPO.
- `TO CONFIRM (organisational)`: the DPO's privacy and data-protection risks (MO-048) and the DPO's view of every risk marked DPO input.
- `TO CONFIRM (organisational)`: every acceptance in §9, including the expiry of R-SHARED-01 on 2026-10-21.
- `TO CONFIRM (organisational)`: the go-live date that the "before go-live" targets depend on.
- `TO CONFIRM (organisational)`: owner conflicts for R-SUP-S09, R-SUP-S10 and R-SUP-S12 (MO-001 §3.4).
- `NOT IN REPO (live cloud)`: any rating that depends on a deployed environment (backups, failover, alerts, edge) is rated on design only.
