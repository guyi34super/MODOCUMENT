# Branch protection and CI security gates (ws19)

Covers gate items **A8** (CI demonstrably blocks a seeded vulnerable PR),
**A7** (gitleaks full-history scan), **E3** (vulnerability scanning) and
**E8** (SBOM per release), controls SEC-009/010/026/029/042, SUP-S05 and the
PRIV-004 PR privacy checklist.

## Required settings for `main`

Branch protection cannot be committed as code — a repository admin applies
these once in **Settings → Branches → Add branch ruleset / protection rule**
for `main`, and this document is the reference for what "correctly
configured" means:

| Setting | Value |
| --- | --- |
| Require a pull request before merging | **On** — no direct pushes to `main` |
| Required approvals | **1** |
| Require review from Code Owners | **On** (`.github/CODEOWNERS`; with "required approvals: 1" this enforces **second-person review** — authors cannot approve their own PRs) |
| Dismiss stale approvals when new commits are pushed | On (recommended) |
| Require status checks to pass before merging | **On**, and "require branches to be up to date" |
| Required status checks | `build-and-test`, `backend-postgres`, `frontend` (CI); `gitleaks`, `semgrep`, `npm-audit` (Security); `dependency-review` (Security, PRs) |
| Allow force pushes | **Off** |
| Allow deletions | **Off** |
| Require signed commits | Optional for now — recommended once all committers have signing set up; enabling it earlier blocks the web-UI merge queue for unsigned bots. Revisit with ws20. |

Notes:

- The required-check names are the **job ids** as GitHub reports them. After
  the first run of `security.yml` they appear in the picker.
- `dependency-review` only runs on PRs — mark it required; GitHub treats a
  check that is skipped on non-PR events correctly because only PRs merge
  into a protected `main`.
  On a **private** repository it only works once Dependency graph and
  GitHub Code Security are enabled (Settings → Code security); without
  them it fails with "Dependency review is not supported on this
  repository" (security roadmap, "Remaining"). Enable those first, or
  making it required blocks every PR.
- Administrators should **not** be exempted ("Do not allow bypassing the
  above settings"), otherwise the second-person guarantee has a hole.

## The scanning gauntlet (what runs where)

| Workflow | Trigger | Jobs / purpose |
| --- | --- | --- |
| `.github/workflows/ci.yml` | push/PR to main | build + tests (H2), real-PostgreSQL suite, frontend; JaCoCo coverage summary |
| `.github/workflows/security.yml` | push/PR to main + weekly | `gitleaks` (full history, A7), `semgrep` (p/java, p/owasp-top-ten, `.semgrep/modocument.yml`), `dependency-review` (PRs), `npm-audit` (fail on high) |
| `.github/workflows/sbom.yml` | main pushes + releases | CycloneDX SBOMs (backend maven plugin via `-Psbom`, frontend `npm sbom`) uploaded as artifacts — **E8** |
| `.github/workflows/image-rebuild.yml` | weekly + manual | rebuild images from current base tags (`--pull`), Trivy fails on CRITICAL — **SEC-026 / E3** |
| `.github/workflows/zap-baseline.yml` | weekly + manual | ZAP baseline against the compose stack, report artifact — **E3/E1** |
| `.github/dependabot.yml` | weekly | maven, npm, github-actions, docker and (since the ws20 integration) terraform updates, grouped minor/patch — **SEC-010** |

All workflows run with `permissions: contents: read` and actions pinned by
full commit SHA with the tag in a comment (SEC-042, SUP-S05).

### gitleaks allowlist discipline (A7)

`.gitleaks.toml` extends the default ruleset and allowlists **only** the
documented legacy dev/test material (the rejected legacy dev JWT secret, the
log-redaction canary JWT, the fixed AES test vector); `.gitleaksignore` pins
six historical findings by exact fingerprint (four from the log-redaction and
MFA tests; two production-shaped but test-only key literals in the ws17
`ApiDocsExposureTest`, added at integration). Any other finding is a
real failure: **rotate the credential first**, then deal with history.
Allowlist additions require a justification comment and security review.

### npm audit overrides

`npm-audit` fails on High/Critical and its `continue-on-error` stays `false`.
When a High advisory has no upstream fix, the override path is a reviewed
[`overrides`](https://docs.npmjs.com/cli/v10/configuring-npm/package-json#overrides)
entry in `frontend/package.json` forcing the patched transitive version — each
such entry gets a comment-style changelog line in the PR and an entry in this
section (currently: none). Softening the audit level or the job is not an
accepted override.

Upstream bumps taken instead of overrides (no `overrides` entry needed):

| Date | Advisory | Fix |
|---|---|---|
| 2026-10-09 (integration of ws18-20) | tinypool <=2.1.1 prototype-pollution RCE gadgets, GHSA-5gmw-xhrv-c9v3 / GHSA-85c8-ppgw-ccpr (critical); @vitest/mocker path traversal, GHSA-82fw-gwwq-j7x9 (moderate) — both dev-only, via vitest 3 | `vitest` ^3.1.0 → ^4.1.11 (vitest 4 no longer depends on tinypool; same config, all 236 frontend tests green) |
| 2026-10-09 | source-map-js 1.2.1 event-loop DoS, GHSA-68fv-2mgg-jv7q (high; via vite/postcss and @tailwindcss/node) | `npm audit fix` → source-map-js 1.2.2 (lock only) |

### Coverage gate

JaCoCo now reports on every `mvn verify` and CI prints the instruction
coverage. There is deliberately **no threshold `<check>` yet**: the first
step is to establish and agree the baseline from a few weeks of runs, then
add a ratchet (fail under baseline-minus-tolerance) so the gate never blocks
on day one with an arbitrary number.

## A8 evidence runbook: the seeded-vulnerable-PR drill

Goal: demonstrate, with a run link, that a PR carrying (a) a hardcoded cloud
credential and (b) weak crypto **cannot merge**: gitleaks and semgrep both
fail, the merge button is blocked by required checks, and a second-person
approval is additionally required.

Run the drill quarterly and after any change to the scanning workflows.

1. Branch off main:

   ```bash
   git checkout -b drill/seeded-vulnerable-pr
   ```

2. Seed finding 1 — a hardcoded AWS key (in a comment, so nothing real
   executes). Append to `src/main/java/com/modocument/MoDocumentApplication.java`
   a comment of the form:

   ```java
   // DRILL A8 — seeded fake credential:
   // aws_access_key_id = <SEED_KEY>
   ```

   where `<SEED_KEY>` is the two halves **`AKIA` + `Q2J4ZZDRILLSEED7`
   concatenated into one 20-character token** (deliberately split here so
   this runbook itself never trips the scan on `main`).

   Do **not** use AWS's documentation example key `AKIAIOSFODNN7EXAMPLE` —
   it sits on gitleaks' built-in global allowlist and will NOT fail the scan
   (verified against gitleaks 8.21.2). The seed above was verified to fire
   rule `aws-access-token`.

3. Seed finding 2 — an MD5 usage (compiles; flagged by
   `modocument-weak-hash-messagedigest` and by `p/java`). Add inside any
   class on the branch:

   ```java
   static String drillWeakHash(byte[] in) throws Exception {
       return java.util.HexFormat.of()
               .formatHex(java.security.MessageDigest.getInstance("MD5").digest(in));
   }
   ```

4. Commit, push, open a PR against `main`. Fill the PR template honestly
   ("A8 drill").

5. Expected result, to be captured as evidence:
   - `gitleaks` job **fails** (the seeded AWS key; fingerprint reported).
   - `semgrep` job **fails** (`modocument-weak-hash-messagedigest` on the MD5
     line; registry rules typically fire too).
   - The PR's merge button is **blocked** ("Required statuses must pass"),
     and shows "Review required" from CODEOWNERS.

6. File the evidence: screenshot of the blocked merge box + the two failing
   check runs, and the run URLs, into the security evidence store under
   `A8/<date>/`.

7. Close the PR **without merging** and delete the branch:

   ```bash
   git push origin --delete drill/seeded-vulnerable-pr
   ```

   The seeded AWS key now exists in the closed PR's branch history only, not
   in `main`'s history, so the scheduled full-history scan of `main` stays
   clean. Do **not** allowlist the drill findings — the next drill must fail
   the same way.
