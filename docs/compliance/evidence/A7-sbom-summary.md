# A7 — Release SBOM summary (SEC-042), 2026-10-19

Generated from the version 1.2.0 tree (`pom.xml`, `agent/pom.xml`, `frontend/package-lock.json`) by run R7 in [A7-runs.md](A7-runs.md). The full CycloneDX files are kept because each is under 2 MB:

| SBOM | File | Spec | Components | Scope |
|---|---|---|---|---|
| Backend API (`modocument` 1.2.0, includes `modocument-connector-core` 1.2.0) | `docs/compliance/evidence/sbom/A7-backend-1.2.0.cdx.json` | CycloneDX 1.6 | 209 | compile + runtime (test scope excluded) |
| Connector Agent (`modocument-agent` 1.2.0) | `docs/compliance/evidence/sbom/A7-agent-1.2.0.cdx.json` | CycloneDX 1.6 | 34 | compile + runtime |
| Frontend SPA (`modocument-frontend` 1.2.0) | `docs/compliance/evidence/sbom/A7-frontend-1.2.0.cdx.json` | CycloneDX 1.6 | 35 | production dependencies (`--omit dev`) |

These are **source-level** SBOMs. The release pipeline also produces **image-level** SBOMs with Trivy (OS packages + application jars) for the API, frontend and agent images (`.github/workflows/release.yml`, steps "SBOM (… image)"). No image SBOM exists yet, because no release tag has been pushed (R9).

Licence names are shown as each component declares them. "OR" means the component is offered under either licence (dual licensing); the most permissive option applies.

## Backend (209 components)

| Licence (as declared) | Components |
|---|---|
| Apache-2.0 | 170 |
| BSD-3-Clause | 10 |
| MIT | 7 |
| EPL-1.0 OR LGPL (logback) | 2 |
| EPL-2.0 OR GPL-2.0-with-classpath-exception | 2 |
| LGPL-2.1-only | 1 |
| BSD-3-Clause OR EPL-2.0 | 1 |
| EPL-2.0 | 1 |
| Bouncy Castle Licence (MIT-style) | 1 |
| BSD-2-Clause | 1 |
| EPL-1.0 OR MPL-2.0 | 1 |
| Oracle Free Use Terms and Conditions (FUTC) | 1 |
| LGPL-2.1-or-later | 1 |
| None declared (`modocument-connector-core`, our own code) | 1 |
| BSD-2-Clause OR CC0-1.0 | 1 |
| CC0-1.0 | 1 |
| BSD-3-Clause OR EPL-2.0 OR GPL-2.0-with-classpath-exception | 1 |
| BSD-4-Clause | 1 |
| "BSD licence" (antlr-runtime) | 1 |
| MIT-0 | 1 |
| Apache-2.0 OR LGPL-2.1-or-later | 1 |
| BSD 3-clause with nuclear disclaimer (jai-imageio-core) | 1 |
| "asl" (jboss-vfs, Apache Software Licence) | 1 |

## Connector Agent (34 components)

| Licence (as declared) | Components |
|---|---|
| Apache-2.0 | 23 |
| MIT | 4 |
| EPL-1.0 OR LGPL (logback) | 2 |
| EPL-2.0 OR GPL-2.0-with-classpath-exception | 1 |
| BSD-2-Clause | 1 |
| LGPL-2.1-or-later | 1 |
| Oracle Free Use Terms and Conditions (FUTC) | 1 |
| None declared (`modocument-connector-core`) | 1 |

## Frontend (35 components)

| Licence | Components |
|---|---|
| MIT | 28 |
| ISC | 7 |

## Copyleft and special-terms components (for the licence policy)

| SBOM | Component | Version | Licence(s) | Classification under `docs/compliance/policies/open-source-licence-policy.md` |
|---|---|---|---|---|
| backend, agent | `org.mariadb.jdbc:mariadb-java-client` | 3.5.10 (backend), 3.5.3 (agent) | LGPL-2.1-or-later | **Weak copyleft — restricted (allowed as an unmodified, separately replaceable jar)** |
| backend | `org.hibernate.orm:hibernate-core` | 6.6.18.Final | LGPL-2.1-only | Weak copyleft — restricted (unmodified library) |
| backend, agent | `ch.qos.logback:logback-classic`, `logback-core` | 1.5.18 | EPL-1.0 OR LGPL | Weak copyleft, dual — EPL-1.0 option used; restricted (unmodified) |
| backend | `org.aspectj:aspectjweaver` | 1.9.24 | EPL-2.0 | Weak copyleft (file-level) — restricted (unmodified) |
| backend | `com.h2database:h2` | 2.3.232 | EPL-1.0 OR MPL-2.0 | Weak copyleft (file-level) — restricted. Note: it is `runtime` scope in `pom.xml`, so it ships in the production jar although only the test profile uses it |
| backend, agent | `jakarta.annotation-api`, `jakarta.transaction-api` | 2.1.1, 2.0.1 | EPL-2.0 OR GPL-2.0 with Classpath Exception | Allowed (Classpath Exception; EPL option) |
| backend | `jakarta.persistence-api`, `org.eclipse.angus:jakarta.mail` | 3.1.0, 2.0.3 | BSD-3-Clause OR EPL-2.0 (OR GPL-2.0-CPE) | Allowed (BSD option) |
| backend | `net.java.dev.jna:jna` | 5.14.0 | Apache-2.0 OR LGPL-2.1-or-later | Allowed (Apache option) |
| backend, agent | `com.oracle.database.jdbc:ojdbc11` | 23.26.3.0.0 (backend), 23.7.0.25.01 (agent) | Oracle FUTC (proprietary, free use) | **Not open source — needs legal approval** (redistribution terms) |
| backend | `org.antlr:ST4` | 4.3.4 | BSD-4-Clause (advertising clause) | Restricted — attribution notice required |

No strong-copyleft licence (GPL without exception, AGPL, SSPL) appears in any of the three SBOMs.

## Observations

1. **Version drift between server and agent:** `mariadb-java-client` 3.5.10 vs 3.5.3 and `ojdbc11` 23.26.3.0.0 vs 23.7.0.25.01. Dependabot covers `/agent` (`.github/dependabot.yml`), but the two projects do not share these pins.
2. **H2 in the production artifact:** `pom.xml` declares `com.h2database:h2` with `runtime` scope, so it is packaged although only tests and the H2 profile use it.
3. **Internal component without a licence:** `modocument-connector-core` declares no licence. It is MO Intelligence's own code; add the proprietary licence to its `pom.xml` so SBOM consumers can tell.
4. **CI frontend SBOM is incomplete:** see run R7b in [A7-runs.md](A7-runs.md) — `npm sbom --omit dev` drops `react`, `react-dom` and `scheduler`.
