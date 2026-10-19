# A7 — Change trail sample and CI state (SEC-025, SUP-S05), 2026-10-19

Source: the local git history of this repository and the GitHub API for `guyi34super/MODOCUMENT` (pull requests, reviews, check runs, workflow runs, tags, releases), read on 2026-10-19 between 16:20 and 16:40 UTC by Agent A7. Run R9 in [A7-runs.md](A7-runs.md).

## 1. Sample release trail: version 1.2.0

The question a reviewer asks: for one production change, can we find who, what, when and the approval chain in minutes? 1.2.0 is the last version with a dated `CHANGELOG.md` heading.

| Step | What the record shows | Where |
|---|---|---|
| Request / scope | PR #32 "v1.2.0 optimization: faster APIs and pages, smaller image, S3 storage, audit log viewer, shared connector-core", opened 2026-10-11 07:04 UTC. Its body holds the change summary, test results, security checklist and privacy checklist from `.github/pull_request_template.md`. | GitHub PR #32 |
| Change content | 31 commits, 528 files. Release notes in `CHANGELOG.md` section `[1.2.0] - 2026-10-11`. | `CHANGELOG.md` |
| Version bump | Commit `c51ac6c` "Release 1.2.0" (2026-10-11 06:55 UTC) sets 1.2.0 in `pom.xml`, `agent/pom.xml`, `connector-core/pom.xml`, `frontend/package.json` and the lock, and dates the changelog heading. Made with `scripts/bump-version.sh`; consistency is checked by `scripts/check-version-sync.sh`. | git history |
| Tests on the PR head | 11 check runs on 2026-10-11 07:16–07:21 UTC: 10 passed (`build-and-test`, `backend-postgres`, `storage-s3`, `frontend`, `Connector core`, `Connector Agent`, `E2E`, `gitleaks`, `semgrep`, `npm-audit`), **`dependency-review` failed** (the PR text says: a repository setting). | GitHub check runs of PR #32 |
| QA sign-off | "GO for v1.2.0-rc.1" | `docs/qa/optimization-signoff.md` |
| Approval | **No review recorded** (0 reviews). Merged by the PR author's account on 2026-10-12 06:02 UTC, merge commit `64160f9`. | GitHub PR #32 reviews |
| Release tag and pipeline | **None.** The repository has no tags and no GitHub Releases, so `.github/workflows/release.yml` (staging, GitHub Release with SBOMs, production approval) has never run for 1.2.0. Nothing is deployed (pre-production, workbook statement of 2 Oct 2026). | GitHub tags and releases: empty |
| Deployment record | Not applicable yet. When deployed, the app audits `VERSION_DEPLOYED` once per version (`DeployedVersionRecorderTest`), and the ECS task definition pins images by digest. | `src/main/java/com/modocument/infrastructure/release/DeployedVersionRecorder.java` |

What can be retrieved in minutes today: the code change, its author and time, test results and the changelog. What cannot: an independent approval (no review) and a deployment record (no release run).

## 2. Recent merges to `main` (first-parent history)

| Merge | Date (UTC+4) | PR | Checks | Reviews |
|---|---|---|---|---|
| `23647fe` | 2026-10-18 16:46 | #66 Validation on/off per PDF template and document | 11 of 11 **failed in 1–2 s** (no runner) | 0 |
| `2bff7ed` | 2026-10-17 21:18 | #65 | sampled check runs **failed in 2 s** (2026-10-17 17:18 UTC) | 0 |
| `d6bfdc4` | 2026-10-17 12:06 | #64 | sampled check runs **failed in 2 s** (2026-10-17 08:06 UTC) | 0 |
| `e879bcf` | 2026-10-16 21:08 | #63 Document creation: checks and rule mapping | all sampled check runs **failed in about 2 s** | 0 |
| `8e30613` | 2026-10-16 13:14 | #62 | ran normally (minutes); passed except `dependency-review` (failed) | 0 |
| `64160f9` | 2026-10-12 10:02 | #32 v1.2.0 | 10 passed, `dependency-review` failed | 0 |

The PR #66 merge commit message reads: "Merged on the local gate while GitHub Actions is not starting jobs (no runner assigned): 2,165 H2 + 96 PostgreSQL tests, 773 Vitest tests and the build, E2E 102 passed / 0 failed, gitleaks, actionlint and semgrep." Those local results are the author's statement; this pack did not re-run them.

## 3. Findings

| # | Finding | Effect | Control |
|---|---|---|---|
| F1 | **GitHub Actions has not started jobs since PR #63 (2026-10-16).** Every workflow run (push, PR, schedule, Dependabot) fails in 1–4 seconds without a runner. Examples: `security.yml` scheduled run on 2026-10-19 10:56 UTC (jobs `gitleaks`, `semgrep`, `npm-audit` failed in 2 s); CI runs of Dependabot PRs #67–#71. | No automated gate has checked the last four merges (#63–#66). Weekly scans and Dependabot PR checks give no signal. | SEC-025, SUP-S05, SEC-042 |
| F2 | **PRs #63–#66 merged on a "local gate"**, with red required checks. | The documented rule "Require status checks to pass before merging" (`docs/security/branch-protection.md`) is not enforced, or an admin bypass is used. | SEC-025, SUP-S05 |
| F3 | **No second-person review** on any sampled merge (#32, #62, #63, #64, #65, #66: 0 reviews each), each merged by the account that opened it. `.github/CODEOWNERS` lists one account. | The four-eyes rule for code changes is not met in practice. | SEC-025, SUP-S05 |
| F4 | **`dependency-review` fails on every PR** (#32, #62) and merges proceed. | The dependency vulnerability gate is not effective. | SEC-042, SUP-S05 |
| F5 | **No tags or releases exist.** | The release pipeline, its approvals and its SBOM attachment are designed but never exercised. | SEC-025, SEC-042 |

Account billing, runner settings and the applied branch-protection ruleset are GitHub settings: `NOT IN REPO (live cloud)`. The cause of F1 is `TO CONFIRM (organisational)`.
