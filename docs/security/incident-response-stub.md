# Incident response — stub (PRIV-023)

**Status: STUB.** The full incident-response pack is **pack-09 — pending**
(owner: Rushal Seeruthun, with Reezvi Pydiah for notification decisions).
This stub exists so (a) the evidence folder has the anchor other documents
link to, and (b) the minimum viable path is written down until pack-09
lands. Do not represent this as the finished IR plan.

## Until pack-09 lands: the minimum path

1. **Report**: anything suspected → `security@modocument.example`
   (gate E5 mailbox — must be live before go-live; see
   `frontend/public/.well-known/security.txt` and the root `SECURITY.md`)
   and directly to Rushal Seeruthun. External researchers use the same
   address.
2. **Triage (first hour)**: confirm signal from the sources that already
   exist — the security alerts raised by the ws18 rules (webhook/e-mail,
   and Administration → Security alerts / `GET /api/admin/alerts` with
   delivery evidence; catalogue in
   [alerting-and-export-caps.md](alerting-and-export-caps.md)), the
   append-only audit trail (`AuditEvent`: `LOGIN_FAILED`, `ACCOUNT_LOCKED`,
   `TOKEN_REFRESH_REUSE_DETECTED`, `EXPORT_BLOCKED`, denied events, `DSR_*`,
   `USER_*`; per bank also via the SIEM feed `GET /api/siem/events`), app
   logs (redacted; alerts carry the `SECURITY_ALERT` tag), and once applied,
   GuardDuty/CloudTrail/alarms (`infra/terraform/modules/security-monitoring`;
   fix DEF-2 in `docs/compliance/evidence/A8-infra-checks.md` first — as
   written, the alert topic's AWS-managed key would stop GuardDuty findings
   and alarms from being delivered). Availability incidents: the external
   probe on `GET /api/probe/synthetic` once a monitor exists
   ([sla-and-status.md](sla-and-status.md)); flood or abuse:
   [ddos-runbook.md](../compliance/policies/ddos-runbook.md).
3. **Contain** with controls that already exist:
   * compromised account → disable via maker/checker (break-glass: a second
     admin approves immediately); password change bumps the token version —
     every session dies;
   * compromised JWT/MFA key → rotate per
     [key-certificate-register.md](key-certificate-register.md) + rolling
     restart;
   * tenant-isolation suspicion → RLS is fail-closed; snapshot the DB
     before any change (evidence);
   * destructive action or ransomware → database recovery points are in the
     backup vault (locked only once `enforce_backup_locks = true` in
     production) and in the ap-southeast-2 copy; restore procedures R1–R5
     in [storage-backup-recovery-map.md](../compliance/policies/storage-backup-recovery-map.md)
     (never rehearsed — gate D6 pending). The S3 file store has versioning
     only, with a 7-day window, and no backup copy (DEF-4): act within days.
4. **Preserve evidence**: audit rows are append-only (DB-enforced, V14) and
   never purged by the application; the 7-year archive copy does not exist
   yet (SEC-033,
   [log-retention-and-rehydration.md](../compliance/policies/log-retention-and-rehydration.md)).
   App logs stay 90 days in CloudWatch and flow logs 30 days — export what
   the investigation needs before they expire. Do NOT purge manually;
   export the relevant window via admin tooling (`GET /api/admin/audit`).
5. **Notify**: Reezvi Pydiah decides bank/regulator/data-subject
   notification and deadlines (contractual + GDPR-equivalent); nothing goes
   out without that decision. For any incident involving personal data the
   DPO is informed at once and advises on the notification decision before it
   is made (RACI in `docs/compliance/mo-001-governance-structure.md`; who the
   DPO is remains TO CONFIRM, task MO-038).
6. **Communicate** during the incident: in-app MAINTENANCE or INFO
   announcement to the affected banks (`docs/release/runbook.md` §8); the
   external status page does not exist yet (`TO CONFIRM (organisational)`).
7. **Close**: post-incident review using
   [post-incident-review-template.md](../compliance/policies/post-incident-review-template.md)
   (what, when, blast radius, fix, lesson), filed in this folder.

## What pack-09 must add (open)

- Severity matrix + paging/on-call schedule
- Named deputies and out-of-hours contacts
- Regulatory notification clock-start definitions per bank contract
- Forensic evidence-handling checklist (chain of custody)
- Communication templates (bank, regulator, data subjects)
- Tabletop exercise schedule and first-run date
- Integration with the SIEM reference test (SEC-031) outputs

Related: [threat-control-matrix.md](threat-control-matrix.md) ·
[security-roadmap.md](security-roadmap.md) · [sla-and-status.md](sla-and-status.md)
