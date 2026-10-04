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
   exist — the append-only audit trail (`AuditEvent`: `LOGIN_FAILED`,
   `ACCOUNT_LOCKED`, denied events, `DSR_*`, `USER_*`), app logs
   (redacted), and once applied, GuardDuty/CloudTrail/alarms
   (`infra/terraform/modules/security-monitoring`).
3. **Contain** with controls that already exist:
   * compromised account → disable via maker/checker (break-glass: a second
     admin approves immediately); password change bumps the token version —
     every session dies;
   * compromised JWT/MFA key → rotate per
     [key-certificate-register.md](key-certificate-register.md) + rolling
     restart;
   * tenant-isolation suspicion → RLS is fail-closed; snapshot the DB
     before any change (evidence);
   * destructive action → recovery points are in the (locked) backup vault;
     restore procedure per the D6 drill runbook (pending).
4. **Preserve evidence**: audit rows are append-only and 7-year retained;
   do NOT purge manually; export the relevant window via admin tooling.
5. **Notify**: Reezvi Pydiah decides bank/regulator/data-subject
   notification and deadlines (contractual + GDPR-equivalent); nothing goes
   out without that decision.
6. **Close**: post-incident note (what, when, blast radius, fix, lesson)
   filed in this folder.

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
