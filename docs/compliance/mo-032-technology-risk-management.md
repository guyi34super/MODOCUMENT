# MO-032 — Technology Risk Management Document

**Task MO-032: Risk technology document.** Deliverable: Technology Risk Management Document.
**Linked DPO task:** MO-048 (the DPO confirms that privacy risks and treatments are correctly stated).

This document sets how MO Intelligence manages technology risk for MoDocument: the method, the rating scale, the risk appetite proposed for Board approval, who does what, how often risks are reviewed, how they are escalated, the current top risks with their treatment plans, how risks are monitored, and how they are reported to the Board. The register itself is `docs/compliance/mo-031-technology-risk-register.md` (data: `docs/compliance/risks.yaml`); the controls are in `docs/compliance/mo-033-internal-control-matrix.md` and their test results in `docs/compliance/mo-034-internal-control-audit-report.md`.

## 1. Document control

| Field | Value |
|---|---|
| Document | MO-032 Technology Risk Management Document |
| Version | 1.0 draft |
| Date | 2026-10-19 |
| Status | Draft. Not yet reviewed or approved. |
| Responsible | Bhavish Nobeen (RACI row 20: R). Drafted for the pack by agent A9. |
| Review | Taj Aundoo (risk and control review; checklist MO-032 "Reviewer / Approver") |
| Approval | Board of Directors (RACI row 20: A; checklist "Management Approval: Yes") |
| DPO | Confirms the privacy risks and treatments in §10 (checklist "DPO Action"; MO-048): `TO CONFIRM (organisational)` |
| Review cycle | Yearly; the risk profile in §7 is refreshed every quarter |
| Proposed approval date | 2026-11-02 (proposed), with MO-001 |

| Version | Date | Change | By |
|---|---|---|---|
| 1.0 draft | 2026-10-19 | First version | Bhavish Nobeen (owner); drafted for the pack by agent A9 |

## 2. Scope

- **In scope:** technology risk to the MoDocument platform and the client bank data it processes: hosting and infrastructure, third-party and AI providers, security, access, data loss, availability and performance, AI output accuracy, key-person dependency and regulatory/privacy (the nine themes of checklist MO-031).
- **Out of scope:** commercial and financial risk of the company; each client bank's own risk management.
- **Position on 2026-10-19:** pre-production. The workbooks (2 Oct 2026) record nothing deployed and no bank data held. The risk profile in §7 is rated for the first client go-live with today's controls.

## 3. Method

### 3.1 Process

| Step | What happens | Output |
|---|---|---|
| 1. Identify | Risks come from the three workbooks, the checklist themes, control testing (MO-034), incidents, scans, stress tests, the DPO, and any change to the system or its providers. | New row in MO-031 |
| 2. Assess | Rate inherent and residual likelihood and impact (§3.2). Residual credit is given only for controls the pack proves. | L, I, score, band |
| 3. Treat | Choose a treatment (§3.4), an action, an action owner and a target date. | Treatment plan |
| 4. Accept | Residual High or Critical, or a risk the workbook marks for acceptance, needs a written decision (§6). | Acceptance record |
| 5. Monitor | Key risk indicators (§11) and control test results (MO-034). | KRI dashboard |
| 6. Report | Monthly director review; quarterly report to the Board (§12). | Board report and minutes |

### 3.2 Rating scale

Score = Likelihood × Impact.

| Likelihood | Meaning (next 12 months) |
|---|---|
| 1 Rare | Less than 5 % chance; needs several independent failures |
| 2 Unlikely | 5–20 %; a control exists and is tested, a gap remains |
| 3 Possible | 20–50 %; controls designed but not operating or not tested |
| 4 Likely | 50–90 %; a known gap with no working control |
| 5 Almost certain | Above 90 %, or already happening today |

| Impact | Client data and privacy | Availability | Regulatory and contractual | Integrity of documents |
|---|---|---|---|---|
| 1 Insignificant | None | No client effect | None | None |
| 2 Minor | No bank data exposed | Short degradation within SLA | Internal finding | Error caught before release |
| 3 Moderate | Limited exposure, contained quickly | Outage shorter than the RTO | Audit finding to remediate | Error in a draft seen by a bank |
| 4 Major | One bank's personal data exposed | Outage longer than the RTO; SLA breach | Regulator finding; failed due diligence | Wrong figures in a released document |
| 5 Severe | Several banks' data, or data at scale; data lost | Loss of service for days; data not recoverable | Regulator notification; loss of a client | Systematic wrong output across banks |

| Score | Band |
|---|---|
| 1–4 | **Low** |
| 5–9 | **Medium** |
| 10–16 | **High** |
| 20–25 | **Critical** |

### 3.3 Inherent and residual

- **Inherent:** the rating with no control.
- **Residual:** the rating with the controls the pack proves on the rating date. A control that exists only in unapplied Terraform, or only on paper, earns credit only as far as the pack shows it would work. Each MO-031 row names its proof.

### 3.4 Treatments

| Treatment | When | Example in MO-031 |
|---|---|---|
| Mitigate | Reduce likelihood or impact with a control | R-TECH-08: upgrade the libraries |
| Accept | Residual is within appetite, or the cost of treatment is out of proportion; written decision (§6) | R-REGION-01: no warm standby (cost decision) |
| Transfer | Move the financial impact to a third party (insurance, contract). None used today: cyber insurance and contract terms are `TO CONFIRM (organisational)`. | — |
| Avoid | Stop the activity that creates the risk | R-SEC-031b: connect no bank SIEM until scoping is accepted |

## 4. Roles and review frequency

Roles come from the RACI in `docs/compliance/mo-001-governance-structure.md` §7.

| Role | Who | Duties |
|---|---|---|
| Board of Directors | Board | Approves this document and the risk appetite; receives the quarterly report; decides Critical risks and High acceptances (proposed — confirm) |
| Risk acceptance | Reezvi Pydiah | Decides acceptances (approval matrix D11) and budget for treatments (D13); chairs the proposed oversight forum |
| Risk and control review | Taj Aundoo | Reviews the register, the control matrix and the audit (MO-031…034); challenges ratings |
| Risk owner | Bhavish Nobeen (every workbook risk, D11); named owners for the new risks | Keeps the rating and the plan current; reports KRIs |
| Deputy and operations | Rushal | Deputy per item (R-PEOPLE-02); restore drill, load-test run, incident owner; independent tester of Bhavish-owned controls |
| The DPO | `TO CONFIRM (organisational)` (MO-038) | Supplies and confirms privacy risks (MO-048) and data-protection controls (MO-059) |

| Activity | Frequency | Forum |
|---|---|---|
| KRI dashboard (§11) | Monthly | Monthly director review of the security roadmap (02 workbook SEC-041) |
| Top-10 risks and overdue actions | Monthly | Same |
| Full register re-rating | Quarterly | Technology Risk & Compliance Committee (proposed, MO-001 §9) |
| Board risk report (§12) | Quarterly | Board of Directors |
| Control audit (MO-034) | Yearly, and before the first go-live | Taj Aundoo review; Board approval |
| Event-driven review | Within 5 working days | After an incident with personal-data impact, a failed control test, a stress or penetration test, a new provider, a new bank, or a major release |

## 5. Risk appetite (proposed for Board approval)

**Statement (proposed — confirm).** MO Intelligence processes client banks' customer data. It has **no appetite** for any exposure of one bank's data to another bank, and a **low appetite** for any risk that could expose personal data, break a regulatory or contractual duty, or put a wrong figure in a bank's released document. It accepts **moderate** risk to availability within the SLA and to delivery dates, provided the client is told in time. It does not go live with a client while a Critical residual risk is open, or while a High residual risk in the confidentiality or regulatory categories has no written acceptance by the Board.

**Tolerance per category (proposed — confirm).**

| Category (MO-031 themes) | Appetite | Highest residual band the risk owner may carry with Reezvi Pydiah's acceptance | Board decision needed | Tolerance signal |
|---|---|---|---|---|
| Cross-tenant data exposure | None | Low | Any rating above Low | Any failed tenant-isolation test or probe |
| Confidentiality: security, access, data loss | Low | Medium | High or Critical | Critical or High vulnerability past SLA; privileged account without grant record |
| Regulatory / privacy | Low | Medium | High or Critical | DSR or breach-notification deadline missed; DPIA unsigned for live processing |
| AI output accuracy and document integrity | Low | Medium | High or Critical | Wrong figure in a released document |
| Availability / performance | Moderate | High (before go-live only) | Critical; High after go-live | Monthly availability below 99.5 %; restore drill older than 90 days; p95 above target at planned load |
| Hosting / infrastructure and third parties | Moderate | High (before go-live only) | Critical | Provider outage or billing stop not detected within one day |
| Key-person dependency and delivery | Moderate | High | Critical | Two consecutive slipped dates on one owner (workbook R-PEOPLE-02 rule) |

On 2026-10-19 the register holds 2 Critical and 22 High residual risks. Against this appetite, the Critical risks block go-live until treated, and each High confidentiality or regulatory risk needs a Board decision.

## 6. Escalation

| Residual band | Who decides | Timing | Go-live effect |
|---|---|---|---|
| Low | Risk owner | Next quarterly review | None |
| Medium | Risk owner; Reezvi Pydiah informed | Treatment plan within 90 days, or acceptance at the quarterly review | None |
| High | Reezvi Pydiah accepts in writing or approves the plan; reported to the Board each quarter (Board decides for confidentiality and regulatory categories) | Plan within 30 days | Allowed only with a written acceptance (Go-Live Security Gate, approval matrix D4) |
| Critical | Board of Directors | Within 5 working days of the rating | Blocked |

Any person may raise a risk to the risk owner or to Reezvi Pydiah. An incident with personal-data impact goes at once to Rushal, Reezvi Pydiah and the DPO (`docs/security/incident-response-stub.md`; DPO step proposed in MO-001 F1).

## 7. Current risk profile (2026-10-19)

The register holds 80 risks: 64 from the workbooks and 16 new. Residual bands: 2 Critical, 22 High, 46 Medium, 10 Low. Heat maps: MO-031 §3.

## 8. Top ten risks and treatment plans

| Rank | Risk | Residual | Treatment plan | Action owner | Target date | Status |
|---|---|---|---|---|---|---|
| 1 | **R-TECH-08** Eleven critical library CVEs unpatched past the 72-hour limit (issue #52) | 4×5 = 20 Critical | Mitigate: Upgrade Spring Boot and libraries, rebuild, re-scan; written acceptance for any CVE without a 6.2.x fix. | Bhavish Nobeen; Reezvi Pydiah (acceptance) | 2026-10-22 (proposed; overdue since 2026-10-15) | Open — overdue |
| 2 | **R-TECH-09** CI not running and branch protection not enforced | 5×4 = 20 Critical | Mitigate: Restore billing and runners; re-run CI on `main`; apply the ruleset without bypass; second code owner. | Bhavish Nobeen | 2026-10-22 (CI), 2026-10-30 (ruleset) (proposed) | Open |
| 3 | **R-PEOPLE-02** One person owns almost every remediation action | 4×4 = 16 High | Accept + mitigate: Assign a second named owner for at least the Day 30 items; Board reviews the load quarterly (MO-032). | Board of Directors; Reezvi Pydiah | 2026-11-02 (proposed) | Accepted (management decision) |
| 4 | **R-SEC-032** Attacks proceed for days; first notice comes from the bank | 4×4 = 16 High | Mitigate: On-call relay; pass `MODOCUMENT_ALERTS_*` in the ECS task; trip a HIGH rule out of hours with a written record; approve the escalation policy. | Bhavish Nobeen | 2026-10-30 (overdue workbook date 2026-10-16) | Open |
| 5 | **R-SEC-010** Vulnerabilities and secrets merge unexamined | 4×4 = 16 High | Mitigate: Apply the ruleset without bypass; second code owner; export approvals quarterly. | Bhavish Nobeen | 2026-10-30 (proposed; workbook 2026-10-24) | Open |
| 6 | **R-PRIV-006** Backup failure discovered only during a real incident | 3×5 = 15 High | Mitigate: Timed restore drill (gate D6) in staging and production; then quarterly; agree one RTO. | Rushal (drill); Bhavish Nobeen (DR lead) | Before go-live, with the first `terraform apply` (date `TO CONFIRM (organisational)`) | Open |
| 7 | **R-SEC-009** Exploitable vulnerabilities unknown to us but findable by attackers | 3×4 = 12 High | Mitigate: Budget decision and external penetration test with retest; monthly VA report. | Reezvi Pydiah (budget); Bhavish Nobeen (remediation) | 2026-10-31 (budget decision) | Open |
| 8 | **R-SEC-017** Unknown or shared admin accounts operate unwatched | 3×4 = 12 High | Mitigate: Grant record approved by Reezvi Pydiah; separate admin identities; first monthly review; add PLATFORM_ADMIN to the admin-login alert. | Bhavish Nobeen; Reezvi Pydiah (grant record) | 2026-10-30 | Open |
| 9 | **R-SEC-026** Known-exploited vulnerabilities stay open for months | 3×4 = 12 High | Mitigate: Approve the patch policy and one critical limit; instance refresh; monthly patch report. | Bhavish Nobeen | 2026-10-22 | Open |
| 10 | **R-AI-01** Non-adopted AI controls: prompt injection, model-written figures, untested model changes | 3×4 = 12 High | Accept: Reezvi Pydiah signs the C11 acceptance of the four residual risks. | Reezvi Pydiah | 2026-11-02 | Pending written acceptance |

Linked corrective actions in MO-034 §7: R-TECH-08 → CA-01; R-TECH-09 → CA-02, CA-03; R-SEC-032 → CA-04; R-PEOPLE-02 → CA-37; R-SEC-010 → CA-03; R-PRIV-006 → CA-09; R-SEC-009 → CA-08; R-SEC-017 → CA-12; R-SEC-026 → CA-01, CA-19; R-AI-01 → CA-38.

## 9. Stress-test findings (MO-017)

Source: `docs/compliance/mo-017-stress-testing-report.md` (run 2026-10-19 on a shared 4-vCPU test box, one instance, synthetic data; raw output in `perf/results/stress-2026-10/`). A9 re-computed the reported p95 figures from the raw per-request files and they match (MO-034 §4.2).

**Result.** One instance served 1×, 2× and a 4× spike of an assumed 50-user peak with 0 errors; capacity within the thresholds was about 715 req/s at 300 users on 4 CPUs (latency break at 400 users) and about 236 req/s at 100 users on one CPU (break at 150). Overload controls held: 429 with `Retry-After` at every limit, AI outage absorbed by the circuit breaker, full database pool queued without errors. **What it does not prove:** production capacity. The expected peak is `TO CONFIRM (organisational)` and the staging run (gate E7) is still to do.

| Finding (MO-017 §7) | Severity | Risk effect | Treatment | Owner | Proposed date |
|---|---|---|---|---|---|
| F-01 Full job read returns every row (17.9 MB, 765 ms for 50,000 rows) | Medium | R-TECH-16 | Page the rows; counts only in the default view | Bhavish Nobeen | 2026-12-15 |
| F-02 Ingestion: 2 threads, unbounded queue | Medium | R-TECH-16, R-SEC-002 | Configurable pool; bounded queue with 429 | Bhavish Nobeen | 2026-12-15 |
| F-03 Large re-index holds a thread and a DB connection ~35 s | Medium | R-TECH-16 | Batched indexing on its own executor | Bhavish Nobeen | 2026-12-15 |
| F-04 RSS 2,656 MiB, 160 MiB under the container limit | Medium | R-TECH-16, R-SEC-003 | Lower `MaxRAMPercentage` or raise `app_memory`; re-measure on staging | Bhavish Nobeen | 2026-11-30 |
| F-05 Report generation synchronous, CPU-bound, no queue | Medium | R-SEC-002, R-TECH-16 | Bounded generation executor; 202 + poll or 429 | Bhavish Nobeen | 2026-12-15 |
| F-06 No early shedding under saturation | Low | R-SEC-002 | Align Tomcat threads with the pool; concurrency limiter; autoscaling | Bhavish Nobeen | 2027-01-31 |
| F-07 Rate-limit counters per instance | Low | R-SEC-002, R-SEC-007 | Shared counter store before scaling out | Bhavish Nobeen | 2027-01-31 |
| F-08 XLSX export ~4× slower than CSV | Low | R-TECH-16 | Streamed workbook | Bhavish Nobeen | 2027-01-31 |
| F-09 Soak drift caused by a noisy neighbour | Info | R-SEC-008 | 1-hour soak on staging | Bhavish Nobeen | before go-live |

Effect on the register: R-SEC-008 residual 2×4 = 8 Medium (tested, not on staging); R-SEC-002 2×4 = 8 Medium; R-SEC-003 3×3 = 9 Medium; R-TECH-16 3×3 = 9 Medium. Corrective action CA-33 in MO-034 tracks all of them.

## 10. Privacy risks (DPO, MO-048)

The DPO confirms that these risks and treatments are correctly stated: `TO CONFIRM (organisational)`. They are drafted from the repository.

| Risk | Residual | Treatment | DPO decision needed |
|---|---|---|---|
| R-PRIV-002 Bulk data theft proceeds unnoticed | 8 Medium | Connect the alert phone path (SEC-032); add `AUDIT_LOG_EXPORTED` to an alert rule; egress allow-list under R-TECH-11. | Confirm rating and treatment |
| R-PRIV-004 Unnecessary personal data accumulates | 6 Medium | DPO sign-off of the DPIA; API-wide canary PII test; update the data inventory. | Confirm rating and treatment |
| R-PRIV-013 An unanswered subject request becomes a complaint naming the bank | 8 Medium | Extend erase to titles and photos; cover or forbid the Mongo store; confirm the statutory period with the DPO. | Confirm rating and treatment |
| R-PRIV-017 Ex-staff or over-privileged accounts keep access | 8 Medium | Delete the shared account; ticket per joiner; first quarterly review at go-live. | Confirm rating and treatment |
| R-SUP-S13 Bank data leaves jurisdiction into a model provider; cross-tenant leakage through model output | 8 Medium | Decide the production model location; if external, provider terms and DPA, transfer assessment, egress allow-list entry. | Confirm rating and treatment |
| R-TECH-02 Critical providers managed without contracts, DPAs or exit plans on record | 12 High | Vendor register with owner, contract, DPA, data location, exit plan; billing alerts on GitHub and AWS. | Confirm rating and treatment |
| R-TECH-04 Privacy governance not yet in place | 12 High | Confirm the DPO (MO-038); DPO signs the DPIA; add the DPO step to breach notification; record the controller/processor position. | Confirm rating and treatment |
| R-TECH-05 Cross-border transfer without a recorded legal basis | 9 Medium | DPO records the transfer basis or the region is changed before go-live. | Confirm rating and treatment |
| R-TECH-06 Incident response plan is a stub | 12 High | Write pack-09; run a tabletop before go-live. | Confirm rating and treatment |
| R-TECH-07 Staff confidentiality and awareness not evidenced | 9 Medium | Signed undertaking before access; yearly security and privacy training with a record. | Confirm rating and treatment |
| R-TECH-11 Network egress from production not restricted | 12 High | Sign off the allow-list (gate D3); narrow the CIDRs; fix DEF-8; alert on new destinations. | Confirm rating and treatment |
| R-TECH-15 Colleagues see raw personal data on bank-shared records | 9 Medium | DPO decision: mask colleagues' records or accept with a documented purpose. | Confirm rating and treatment |

## 11. Monitoring: key risk indicators

Values on 2026-10-19 come from the cited files. Thresholds are proposed — confirm.

| KRI | Definition | Value on 2026-10-19 | Green / Amber / Red | Source | Owner | Frequency |
|---|---|---|---|---|---|---|
| K1 Vulnerabilities past SLA | Open Critical (72 h) and High (14 days) findings past their SLA | **11 Critical past SLA**; 41 High within SLA until 2026-10-26 | 0 / High past SLA / any Critical past SLA → **Red** | `docs/compliance/evidence/A5-vulnerability-assessment-report-2026-10.md` | Bhavish Nobeen | Weekly |
| K2 CI green rate | Share of merges to `main` in the month whose required checks ran and passed | **0 of the last 4 merges** (#63–#66: no job started) | ≥ 95 % / 80–95 % / < 80 % → **Red** | `docs/compliance/evidence/A7-github-change-trail.md` | Bhavish Nobeen | Monthly |
| K3 CI outage | Days since the last CI job ran on `main` | **3 days** (last on 2026-10-16) | 0–1 / 2–3 / > 3 → **Amber** | same | Bhavish Nobeen | Daily |
| K4 Unreviewed merges | Merges to `main` with no second-person approval | **6 of 6 sampled** | 0 / 1 / > 1 → **Red** | same | Bhavish Nobeen | Monthly |
| K5 Alert trip tests | Alert rules tripped end to end by a test; months since a HIGH alert reached a person out of hours | **10 of 26 rules**; never reached a person | 26/26 and ≤ 1 month / ≥ 20 / otherwise → **Red** | `docs/compliance/controls/SEC-032.md` | Bhavish Nobeen | Monthly |
| K6 Access review completion | Banks and platform with the quarterly recertification done on time | **None held** (pre-production) | 100 % / ≥ 90 % / < 90 % → not yet due | `docs/security/jml-runbook.md` | Bank ADMINs; Bhavish Nobeen | Quarterly |
| K7 Restore drill age | Days since the last timed restore met the RTO | **Never run** | ≤ 90 / 91–120 / > 120 or never → **Red** | `docs/compliance/policies/storage-backup-recovery-map.md` | Rushal | Quarterly |
| K8 Stress-test p95 | p95 of read requests at 2× the assumed peak against the 500 ms threshold (MO-016 §7) | **21 ms** (4 CPUs) / **93 ms** (one CPU), test box only; staging not run | ≤ 500 ms on staging / ≤ 500 ms on the test box only / > 500 ms → **Amber** (no staging run) | `docs/compliance/mo-017-stress-testing-report.md` | Bhavish Nobeen; Rushal (run) | Before go-live; each major release |
| K9 Overdue roadmap items | Roadmap items past their workbook date | **7** re-proposed | 0 / 1–3 / > 3 → **Red** | `docs/security/security-roadmap.md` | Bhavish Nobeen | Monthly |
| K10 Unaccepted High/Critical | Residual High or Critical risks with no written acceptance | **23 of 24** (R-PEOPLE-02: management acceptance recorded in the workbook) | 0 / 1–3 / > 3 → **Red** | `docs/compliance/mo-031-technology-risk-register.md` §9 | Reezvi Pydiah | Monthly |
| K11 Corrective actions overdue | MO-034 corrective actions past their target date | **0 of 39** (all set on 2026-10-19) | 0 / 1–3 / > 3 | `docs/compliance/mo-034-internal-control-audit-report.md` §7 | Taj Aundoo | Monthly |
| K12 Privileged accounts without a grant record | Cloud and platform admins with no approved grant | **2** (owner statement, `TO CONFIRM (organisational)`) | 0 / — / ≥ 1 → **Red** | `docs/compliance/controls/SEC-017.md` | Bhavish Nobeen | Monthly |

## 12. Reporting to the Board

Each quarter the risk owner prepares, Taj Aundoo reviews, and the Board receives a report in this form. Between reports, the monthly director review sees the KRI dashboard and the top ten.

**Quarterly technology risk report — template**

| Section | Content | Source |
|---|---|---|
| 1. Summary | Three to five sentences: overall position, change since last quarter, decisions asked of the Board | Risk owner |
| 2. Risk profile | Residual heat map; counts by band now and last quarter; risks that moved band | MO-031 §3 |
| 3. Top ten | ID, rating, trend (↑ ↓ →), treatment status, owner, target date | MO-031 §4; this document §8 |
| 4. Outside appetite | Every risk above its category tolerance (§5), with the decision needed | §5; MO-031 §9 |
| 5. KRIs | The §11 table with RAG status and trend | §11 |
| 6. Control assurance | Counts from the latest control audit; Not effective controls; overdue corrective actions | MO-034 §2, §7 |
| 7. Incidents and near misses | Incidents, breaches, notifications, post-incident actions | Incident log (`docs/security/incident-response-stub.md`) |
| 8. Privacy | DPO statement on privacy risks, DSRs, DPIAs and breaches | The DPO |
| 9. Tests and assessments | Stress tests, restore drills, penetration tests, VA reports since the last report | MO-017; VA reports |
| 10. Decisions requested | Acceptances, budget, appetite changes | Reezvi Pydiah |

The Board minutes record each decision. Storage of the reports and minutes: `TO CONFIRM (organisational)`.

## 13. Links

| Document | Use |
|---|---|
| `docs/compliance/mo-031-technology-risk-register.md` | The register, heat maps and acceptances |
| `docs/compliance/risks.yaml` | Register data for the workbooks |
| `docs/compliance/mo-033-internal-control-matrix.md` | Controls that treat each risk |
| `docs/compliance/mo-034-internal-control-audit-report.md` | Control test results and corrective actions |
| `docs/compliance/mo-001-governance-structure.md` | Roles, approval matrix (D11 acceptance), proposed oversight forum |

## 14. Review and approval

| Role | Name | Decision | Date |
|---|---|---|---|
| Responsible | Bhavish Nobeen | `TO CONFIRM (organisational)` | |
| Review | Taj Aundoo | `TO CONFIRM (organisational)` | |
| Privacy risks (MO-048) | The DPO | `TO CONFIRM (organisational)` | |
| Approval, including the risk appetite (§5) | Board of Directors | `TO CONFIRM (organisational)` | |

## 15. To confirm

- `TO CONFIRM (organisational)`: Board approval of the appetite statement and tolerances (§5) and of Board decisions on High risks.
- `TO CONFIRM (organisational)`: the DPO's confirmation of the privacy risks (§10, MO-048).
- `TO CONFIRM (organisational)`: KRI thresholds (§11); the oversight forum (MO-001 §9); storage of Board reports and minutes.
- `TO CONFIRM (organisational)`: cyber insurance or contractual transfer of risk (§3.4).
- `NOT IN REPO (live cloud)`: KRIs that need production data (availability, restore drill, alert delivery).
