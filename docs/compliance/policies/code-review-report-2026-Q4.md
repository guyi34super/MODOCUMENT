# Code review report — 2026 Q4 (to date)

Control **SEC-010** (submit latest code review report). Gate item A8. Related: SUP-S05, SEC-042.

| | |
|---|---|
| Period | 2026-07-01 to 2026-10-19 (the start of the repository's PR history to the date of this pack) |
| Branch | `origin/main` at `23647fe` (merge of PR #66, 2026-10-18) |
| Prepared | 2026-10-19 by Agent A6 from the git history. Human review and sign-off required |
| Sources | `git log` / `git diff` only. The GitHub API was not available to the author, so PR approvals, review comments, check-run results and the branch-protection settings are **not** visible here and are marked `TO CONFIRM (organisational)` |

## 1. How the numbers were produced

```bash
# PR merges on main since 1 July
git log origin/main --merges --since=2026-07-01 --grep='Merge pull request' --format='%h|%ad|%an|%cn|%s' --date=short
# Everything on main's first-parent line (direct pushes show up as non-merge commits)
git log origin/main --first-parent --since=2026-07-01 --format='%h|%ad|%an|%cn|%p|%s' --date=iso
# Per PR: commits, size and authors (merge-base diff of the merged branch)
git rev-list --count <merge>^1..<merge>^2
git diff --shortstat <merge>^1...<merge>^2
git log --format=%an <merge>^1..<merge>^2
```

The per-PR figures were produced by a small script that runs the three commands above for each
merge; its output is reproduced in §3.

## 2. Summary

| Measure | Value |
|---|---|
| Merges into `main` made through GitHub (committer `GitHub`) | **32**: 30 titled "Merge pull request #n" plus 2 with custom titles — `3bc44e2` "Merge the Go-Live Security Gate program (20 workstreams)" (2026-10-10, PR number `TO CONFIRM`) and `57e98ba` phase 2 v1.1.0 (#31, 2026-10-11) |
| Commits pushed directly to `main` without a PR | **6**: `c7f6a27`, `40e65e9`, `f2bf2c9` (2026-07-18), `d3a00e8` (2026-07-27), `b2f7a9d` and `b7cfeea` (2026-10-15) |
| Commits inside the 30 numbered PRs | 183 |
| PR size (30 numbered PRs) | median 41 files and 2,430 inserted lines; 18 PRs insert more than 1,000 lines; largest #32 (528 files, +37,090 / −5,181) |
| Who merged | Every one of the 32 merges was made by the GitHub account `Bhavish11` |
| Commit authors per PR | 17 PRs contain only commits authored `Claude` (the AI coding agent); 2 are mixed (`Claude` and Bhavish Nobeen); 7 contain only Bhavish Nobeen's commits; 4 are Dependabot updates |
| Dependency updates | 4 Dependabot PRs merged on 2026-10-12 (#18, #21, #22, #27) |
| PRs merged without GitHub CI | **4**: #63 (2026-10-16), #64, #65 (2026-10-17), #66 (2026-10-18) — see finding F2 |

## 3. Pull requests merged since 2026-07-01

Size = files changed, lines inserted, lines deleted (merge-base diff). Authors: C = `Claude`
(AI agent), B = Bhavish Nobeen, D = `dependabot[bot]`.

| Date | PR | Commits | Files | + | − | Authors | Touches `.github/workflows` |
|---|---|---|---|---|---|---|---|
| 2026-07-17 | #1 | 1 | 184 | 4,714 | 4,994 | C | yes |
| 2026-07-17 | #2 | 8 | 75 | 4,475 | 153 | C | |
| 2026-07-17 | #3 | 8 | 118 | 8,830 | 44 | C | |
| 2026-07-17 | #4 | 1 | 8 | 482 | 14 | C | |
| 2026-07-18 | #5 | 2 | 69 | 8,246 | 3 | C | yes |
| 2026-07-18 | #6 | 8 | 47 | 1,630 | 582 | C | |
| 2026-08-04 | #7 | 20 | 242 | 16,166 | 1,359 | C + B | |
| 2026-09-23 | #8 | 4 | 56 | 2,809 | 114 | C + B | |
| 2026-09-23 | #9 | 1 | 73 | 6,334 | 3 | C | |
| 2026-09-27 | #10 | 3 | 33 | 2,050 | 85 | C | |
| 2026-10-12 | #32 | 31 | 528 | 37,090 | 5,181 | C | yes |
| 2026-10-12 | #21 | 1 | 2 | 91 | 25 | D | |
| 2026-10-12 | #18 | 1 | 2 | 8 | 8 | D | yes |
| 2026-10-12 | #22 | 1 | 3 | 7 | 7 | D | yes |
| 2026-10-12 | #40 | 3 | 4 | 30 | 22 | C | |
| 2026-10-12 | #27 | 1 | 7 | 21 | 21 | D | yes |
| 2026-10-12 | #53 | 1 | 2 | 39 | 3 | B | |
| 2026-10-14 | #54 | 1 | 4 | 6 | 1 | B | |
| 2026-10-14 | #55 | 1 | 9 | 381 | 20 | B | |
| 2026-10-14 | #56 | 1 | 1 | 5 | 2 | B | |
| 2026-10-14 | #57 | 1 | 1 | 4 | 1 | B | |
| 2026-10-15 | #58 | 1 | 42 | 3,312 | 148 | B | |
| 2026-10-15 | #59 | 1 | 4 | 218 | 15 | B | |
| 2026-10-15 | #60 | 16 | 255 | 21,995 | 10,880 | C | |
| 2026-10-15 | #61 | 7 | 71 | 7,834 | 94 | C | |
| 2026-10-16 | #62 | 21 | 126 | 13,908 | 562 | C | |
| 2026-10-16 | #63 | 7 | 28 | 1,857 | 50 | C | |
| 2026-10-17 | #64 | 15 | 107 | 11,666 | 952 | C | |
| 2026-10-17 | #65 | 9 | 40 | 4,168 | 1,687 | C | |
| 2026-10-18 | #66 | 7 | 50 | 2,909 | 272 | C | |

## 4. Review and CI controls as configured in the repository

| Control | Where | State |
|---|---|---|
| PR template with security and privacy checklists | `.github/pull_request_template.md` | In place since 2026-10-04 (`e43719c`), on `main` since 2026-10-10 |
| Code owners | `.github/CODEOWNERS` | One owner for every path: `@guyi34super` |
| Branch-protection reference | `docs/security/branch-protection.md` | Written; the live setting is `TO CONFIRM` (finding F1) |
| Build and test gates | `.github/workflows/ci.yml` | Jobs `connector-core`, `build-and-test`, `backend-postgres`, `storage-s3`, `agent`, `frontend` (runs `scripts/check-nginx-headers.sh`), `e2e` |
| Security gates | `.github/workflows/security.yml` | `gitleaks` (full history), `semgrep` (`p/java`, `p/owasp-top-ten`, `.semgrep/modocument.yml`), `dependency-review` (PRs), `npm-audit` (fails on High) |
| Supply chain | `.github/workflows/sbom.yml`, `.github/workflows/image-rebuild.yml`, `.github/workflows/zap-baseline.yml`, `.github/dependabot.yml` | SBOM per main push and release; weekly image rebuild with Trivy; weekly ZAP baseline; weekly Dependabot |
| Workflow hardening | `.github/workflows/` | All 58 third-party `uses:` references pinned to a full commit SHA (the 59th is the local reusable `./.github/workflows/ci.yml`); `permissions: contents: read` by default |

Last recorded green GitHub CI on the history: the merge message of `3bc44e2` states that
`build-and-test`, `backend-postgres`, `frontend`, `gitleaks`, `semgrep` and `npm-audit` passed
on `8a9ecba`, and that `dependency-review` cannot run on this private repository until Dependency
graph and GitHub Code Security are enabled.

## 5. Findings

| ID | Finding | Evidence | Severity | Action | Owner | Target |
|---|---|---|---|---|---|---|
| F1 | **Direct pushes to `main`.** Six commits reached `main` without a PR, the last two on 2026-10-15, after the CI gates and the branch-protection reference were merged on 2026-10-10. "Require a pull request before merging" was therefore not enforced on that date | `b2f7a9d`, `b7cfeea` on the first-parent line of `main` | High | Apply the protection rule in `docs/security/branch-protection.md` with "Do not allow bypassing"; export the ruleset as evidence | Bhavish Nobeen | 2026-10-24 (proposed) |
| F2 | **GitHub Actions does not start jobs.** Since PR #63 (2026-10-16) GitHub Actions has not assigned a runner, so no CI job runs. PRs #63, #64, #65 and #66 were merged on the owner's instruction after a local gate; their merge messages record 2,124–2,165 H2 tests, 94–96 PostgreSQL tests, 679–773 Vitest tests, E2E 87–102 passed with 0 failed, plus gitleaks, actionlint and semgrep. These local results are not reproducible run links, and a merge with no green required checks means the checks were not required or were bypassed | merge messages of `e879bcf` (#63), `d6bfdc4` (#64), `2bff7ed` (#65), `23647fe` (#66) | High | Restore runners (GitHub plan, billing or self-hosted runner); re-run CI on `main` and keep the run link; no merge while checks cannot run | Bhavish Nobeen | 2026-10-24 (proposed) |
| F3 | **Second-person review not shown.** `CODEOWNERS` names one account for every path, and all 32 merges were made by one account (`Bhavish11`). With one code owner, an owner-authored PR cannot get an independent code-owner approval. Approvals are not in git | `.github/CODEOWNERS`; §2 | High | Add a second code owner (Rushal is the deputy named in the workbook, R-SCHED-01); export PR approvals for the period | Bhavish Nobeen | 2026-11-06 (proposed) |
| F4 | **AI-authored changes.** 17 of 30 PRs contain only commits by the AI coding agent; the human merge is the only review step visible in git | §3 | Medium | Record a named human reviewer and the checklist outcome on every PR | Bhavish Nobeen | 2026-11-06 (proposed) |
| F5 | **Very large PRs.** 18 of 30 PRs insert more than 1,000 lines; five insert more than 10,000 | §3 | Medium | Split workstreams into reviewable PRs (guide: under 400 changed lines, security paths separate) | Bhavish Nobeen | 2026-11-06 (proposed) |
| F6 | **Seeded vulnerable-PR drill (A8) not evidenced.** No drill branch or run link is in the repository | `docs/security/branch-protection.md` (runbook) | Medium | Run the drill once Actions runs again and file the run links | Bhavish Nobeen | 2026-11-06 (proposed) |
| F7 | **`dependency-review` cannot run** on the private repository until GitHub Code Security is enabled | merge message of `3bc44e2` | Medium | Enable Dependency graph and Code Security, then make the check required | Bhavish Nobeen | 2026-11-06 (proposed) |
| F8 | PR template names a CI gate `backend`; the job id is `build-and-test`, and `e2e` is not listed | `.github/pull_request_template.md` | Low | Align the template comment with the job ids | Bhavish Nobeen | 2026-11-06 (proposed) |

**Positive results.** Secret scanning, SAST, dependency and image scanning, SBOMs and a DAST
baseline are all defined as code; all actions are SHA-pinned; Dependabot PRs were raised and merged.
A gitleaks 8.21.2 scan of `origin/main` made for this pack on 2026-10-19 found no leaks in 270
commits (`docs/compliance/evidence/A6-test-runs.md`).

## 6. Conclusion

The review and scanning **design** is complete in the repository. The **operation** of the
controls in this period is not demonstrated: direct pushes happened, CI has not run since
2026-10-16, and independent approval cannot be shown from git. Until F1–F3 are closed, SEC-010
should be answered as "designed, not yet operating".

| Sign-off | Name | Date |
|---|---|---|
| Prepared | Agent A6 (automated) | 2026-10-19 |
| Reviewed | Bhavish Nobeen | `TO CONFIRM (organisational)` |
| Accepted | Reezvi Pydiah | `TO CONFIRM (organisational)` |
