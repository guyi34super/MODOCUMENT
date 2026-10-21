# Open-source component and licence policy (SEC-042)

| Field | Value |
|---|---|
| Control | SEC-042 Use of open-source components, libraries or queries (workbook 03) |
| Owner | Software Engineering — Bhavish Nobeen |
| Approver | Reezvi Pydiah (proposed — confirm; decision D2 in docs/compliance/mo-001-governance-structure.md §4). Legal review of the "needs approval" class: `TO CONFIRM (organisational)`. |
| Status | Draft 1.0, 2026-10-19. Not yet approved. |
| Review | Every 12 months, and when a new licence class appears in a release SBOM. |

MoDocument is a commercial service for banks. It ships a backend jar and image, a frontend image and an on-premises Connector Agent (jar and image) that banks run. The agent is **distributed** to banks, so licence obligations that apply on distribution apply to it.

## 1. Rules

1. **Inventory.** Every release has a CycloneDX SBOM for each deliverable: backend, frontend and agent. CI makes them per push to `main` (`.github/workflows/sbom.yml`) and per release from the exact images (`.github/workflows/release.yml`, attached to the GitHub Release).
2. **Vulnerabilities.** A new dependency with a known HIGH or CRITICAL advisory is blocked at PR time (`dependency-review` in `.github/workflows/security.yml`); the frontend tree must pass `npm audit --audit-level=high`; release images must have no fixable HIGH/CRITICAL finding (Trivy in `.github/workflows/release.yml`). Accepted risks go into a reviewed `.trivyignore` entry with an expiry date (decision D12 in mo-001).
3. **Updates.** Dependabot opens weekly update PRs for Maven (app, connector-core, agent), npm, GitHub Actions, Docker and Terraform (`.github/dependabot.yml`). Security updates arrive at once. Critical CVEs: patch release within 72 hours (`docs/release/runbook.md` §6).
4. **Licences.** Each new direct dependency is checked against the classes in §2 by the PR reviewer. The PR description names the licence of any new dependency.
5. **No copy-paste.** Code or SQL copied from the internet (snippets, queries) follows the licence of its source. Copied code needs its source and licence in a comment. Code under a licence in the "not allowed" class must not be copied.

## 2. Licence classes

| Class | Licences | Rule |
|---|---|---|
| **Allowed** | Apache-2.0, MIT, MIT-0, BSD-2-Clause, BSD-3-Clause, ISC, CC0-1.0, Bouncy Castle Licence, Unlicense; dual licences where one option is in this class; GPL-2.0 **with Classpath Exception** | Use freely. Keep the notices in the distribution. |
| **Restricted (weak copyleft)** | LGPL-2.1, LGPL-3.0, EPL-1.0, EPL-2.0, MPL-2.0, CDDL; BSD-4-Clause (advertising clause) | Allowed **only as an unmodified library**, linked dynamically, in a separate jar that a user could replace. Do not modify or copy its source into our code. Keep the licence text and, for LGPL, offer the library's source on request when the agent is distributed. Record each one in the table in §3. |
| **Needs approval** | Proprietary "free use" licences (for example Oracle FUTC), any licence not listed here, components with no declared licence | Owner and legal approval before merge (legal: `TO CONFIRM (organisational)`). |
| **Not allowed** | GPL-2.0/3.0 without exception, AGPL-3.0, SSPL, Commons Clause, BUSL, "non-commercial" licences | Must not be added to any deliverable. |

## 3. Current state (release 1.2.0, SBOMs of 2026-10-19)

Source: `docs/compliance/evidence/A7-sbom-summary.md` (licence counts per SBOM) and the SBOM files in `docs/compliance/evidence/sbom/`.

| Deliverable | Components | Allowed | Restricted | Needs approval |
|---|---|---|---|---|
| Backend (`A7-backend-1.2.0.cdx.json`) | 209 | 200 | 7 (mariadb-java-client, hibernate-core, logback-classic, logback-core, aspectjweaver, h2, ST4) | 2 (ojdbc11 — Oracle FUTC; connector-core — no licence declared, own code) |
| Connector Agent (`A7-agent-1.2.0.cdx.json`) | 34 | 29 | 3 (mariadb-java-client, logback-classic, logback-core) | 2 (ojdbc11; connector-core) |
| Frontend (`A7-frontend-1.2.0.cdx.json`) | 35 | 35 (MIT 28, ISC 7) | 0 | 0 |

The "Allowed" column counts the dual-licensed components (jakarta annotation, transaction, persistence and mail APIs; jna) under their permissive option. No component is in the "not allowed" class.

### Restricted components in use

| Component | Licence | Why it is acceptable | Condition |
|---|---|---|---|
| `org.mariadb.jdbc:mariadb-java-client` (backend 3.5.10, agent 3.5.3) | LGPL-2.1-or-later | JDBC driver for bank MariaDB/MySQL sources and targets, used unmodified as a separate jar | Ship the licence text; offer source on request with the agent. Align the agent's version with the backend's. |
| `org.hibernate.orm:hibernate-core` 6.6.18.Final | LGPL-2.1-only | ORM used unmodified through Spring Boot | Licence text in the distribution |
| `ch.qos.logback:logback-classic`, `logback-core` 1.5.18 | EPL-1.0 OR LGPL | Logging, unmodified | EPL-1.0 option |
| `org.aspectj:aspectjweaver` 1.9.24 | EPL-2.0 | Spring AOP runtime, unmodified | Licence text |
| `com.h2database:h2` 2.3.232 | EPL-1.0 OR MPL-2.0 | Test database only | **Should not ship**: change its Maven scope from `runtime` to `test` (`pom.xml`) |
| `org.antlr:ST4` 4.3.4 | BSD-4-Clause | Transitive template engine | Keep the attribution notice |

### Components needing approval

| Component | Licence | Action |
|---|---|---|
| `com.oracle.database.jdbc:ojdbc11` (backend 23.26.3.0.0, agent 23.7.0.25.01) | Oracle Free Use Terms and Conditions | Legal to confirm that redistributing it inside the agent image is allowed (`TO CONFIRM (organisational)`). |
| `com.modocument:modocument-connector-core` 1.2.0 | none declared | Our own code. Add a proprietary `<licenses>` entry to `connector-core/pom.xml`. |

## 4. Known gaps (2026-10-19)

- The licence check is a reviewer duty only. `dependency-review` has no `deny-licenses` or `allow-licenses` list (`.github/workflows/security.yml`), and the job fails on every PR because the repository's dependency graph is not enabled (`docs/compliance/evidence/A7-github-change-trail.md`).
- The CI frontend SBOM drops `react`, `react-dom` and `scheduler` (`docs/compliance/evidence/A7-runs.md`, R7b).
- GitHub Actions has not run since 2026-10-16, so no CI SBOM or scan exists for the current `main`.

Remediation is tracked on the SEC-042 control page (`docs/compliance/controls/SEC-042.md`).
