# Post-incident review template (SUP-S11)

| Item | Value |
|---|---|
| Control | SUP-S11 — Observability, SLO operations and status communication; also used by SEC-005 and SUP-S03 |
| Owner | IT Infrastructure — Bhavish Nobeen |
| Version | 1.0, written 2026-10-19 by agent A8; human review required |
| When | After every incident that breached the availability objective, paged on call, affected a bank, or involved security. Draft within 5 working days. Blameless. |
| Where to file | A copy per incident in `docs/security/` next to `docs/security/incident-response-stub.md`, named `pir-YYYY-MM-DD-<short-name>.md` |

No post-incident review exists yet: there is no production. The first real
review is evidence for SUP-S11.

---

## PIR-YYYY-NN — <short title>

| Field | Value |
|---|---|
| Incident ID | |
| Severity | |
| Status | Draft / Reviewed / Closed |
| Author / reviewers | |
| Banks affected | (codes only; no personal data) |

### 1. Summary

Two or three sentences: what happened, how long, who was affected.

### 2. Impact

| Measure | Value |
|---|---|
| Start / detection / mitigation / resolution (UTC) | |
| User-visible downtime (minutes) | |
| Availability for the month after this incident (from the external probe) | |
| Error budget used (99.5 % monthly ≈ 3 h 39 m) | |
| Data lost or exposed | none / describe (no personal data in this document) |
| Notifications sent (bank, regulator) and when | |

### 3. Timeline (UTC)

| Time | Event | Source (alarm, probe, log, person) |
|---|---|---|
| | | |

### 4. Detection

- How was it detected: alarm, synthetic probe, bank report, staff?
- Time from start to detection. Should a signal have fired earlier?

### 5. Root cause and contributing factors

Ask "why" until the cause is a process or design, not a person.

### 6. What went well / what went badly

### 7. Actions

| # | Action | Type (prevent / detect / mitigate / process) | Owner | Due date | Tracking issue |
|---|---|---|---|---|---|
| 1 | | | | | |

### 8. Communication record

| Time | Channel (in-app announcement, status page, e-mail to bank) | Message summary | Sent by |
|---|---|---|---|

### 9. Sign-off

| Role | Name | Date |
|---|---|---|
| Technical owner | Bhavish Nobeen | |
| Reviewer | | |
