# Endpoint, device and BYOD policy (SUP-S09)

> **Status: PROPOSED — requires management approval.** Drafted 2026-10-19
> by agent A8 from the workbook's target state. Nothing in this policy is
> verified by the repository. Every fact about current devices, tools and
> premises is `TO CONFIRM (organisational)`.

| Item | Value |
|---|---|
| Control | SUP-S09 — Endpoint, device and physical security |
| Owner | IT Infrastructure — Bhavish Nobeen (per workbook); approver: Reezvi Pydiah (`TO CONFIRM (organisational)`) |
| Applies to | Every person and every device that can reach production AWS, the GitHub repository, bank data, or MoDocument admin functions |
| Review | Annually |

## 1. Why

A lost or stolen unencrypted laptop with bank data is a reportable breach
(workbook risk R-SUP-S09). Today laptops hold synthetic data only (workbook,
2 Oct 2026). This policy must be in force before any bank data arrives.

## 2. Company devices

1. **Enrolment.** Every in-scope device is enrolled in a mobile device
   management (MDM) tool before first use. Tool: `TO CONFIRM
   (organisational)` (options named in the workbook: Intune, Jamf,
   JumpCloud).
2. **Disk encryption.** Full-disk encryption on, recovery key escrowed in
   the MDM.
3. **Screen lock.** Automatic lock after at most 5 minutes idle; password
   or biometric to unlock.
4. **Updates.** Operating-system and browser updates automatic; a device
   more than 14 days behind a security update loses access until patched
   (proposed limit).
5. **Malware and EDR.** An endpoint detection and response agent on every
   device, with ransomware behaviour detection on (see PRIV-003). Product:
   `TO CONFIRM (organisational)`.
6. **No daily admin.** Daily work runs without local administrator rights.
7. **Remote wipe.** The MDM can lock and wipe a device; used on loss,
   theft or leaving (feeds the leaver process in
   `docs/security/jml-runbook.md`).
8. **Credentials.** No AWS access keys, database passwords or production
   secrets are stored on devices. Human AWS access must use personal
   accounts with MFA (Go-Live Security Gate item A3; set-up `TO CONFIRM
   (organisational)`); CI already uses OIDC with no static keys
   (`infra/terraform/modules/ci-oidc/main.tf`). Local
   development uses generated, per-machine secrets in a gitignored `.env`
   (`docs/security/key-certificate-register.md`).
9. **Data.** Bank data is not copied to devices. Exports are downloaded
   only when a task needs them and deleted after use.

## 3. Personal devices (BYOD)

Proposed position: **personal devices may not access bank data,
production AWS consoles or MoDocument admin functions.** They may use
company e-mail and chat only through an MDM-managed work profile.
Management may choose the alternative the workbook allows (MDM-managed work
profiles for all access); the choice must be written here before go-live.

## 4. Loss or theft

1. Report within 1 hour to security@ (the gate E5 mailbox, `SECURITY.md`)
   and to Bhavish Nobeen.
2. Lock and wipe through the MDM.
3. Revoke the person's sessions and rotate any credential the device could
   hold (MoDocument: disable the account or force a password change, which
   ends every session — `docs/security/incident-response-stub.md`).
4. Record as a security incident; decide on notification
   (`docs/security/incident-response-stub.md` step 5).

## 5. Physical security

Office location, access control, visitor handling, clean-desk and screen
privacy rules: `TO CONFIRM (organisational)`. No production system runs on
company premises: production is in AWS af-south-1
(`infra/terraform/envs/production/providers.tf`), so data-centre physical
security is AWS's responsibility under the shared-responsibility model.

## 6. Evidence

| Evidence | Owner | Status |
|---|---|---|
| MDM compliance report: 100 % encryption and EDR for in-scope staff | Bhavish Nobeen | Not available — no MDM yet |
| This policy, approved and signed | Reezvi Pydiah | Draft |
| Premises access note | `TO CONFIRM (organisational)` | Not available |
