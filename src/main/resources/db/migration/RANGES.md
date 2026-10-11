# Migration version ranges

Flyway versions are partitioned per workstream so twenty parallel branches
never collide on a version number. **Do not create a migration outside your
own range.**

- **V1–V9** — pre-program baseline (already applied everywhere).
- **V10–V19** — security foundation (workstream 01: IAM core). V12–V14 are
  reserved for the other foundation agents (RBAC, RLS, audit trail).
- Downstream workstream `NN` owns `V(10 + 10×NN)` … `V(19 + 10×NN)`:

| Workstream | Range     |
|-----------:|-----------|
| A02        | V20–V29   |
| A05        | V30–V39   |
| A06        | V40–V49   |
| A07        | V50–V59   |
| A08        | V60–V69   |
| A09        | V70–V79   |
| A10        | V80–V89   |
| A11        | V90–V99   |
| A12        | V100–V109 |
| A13        | V110–V119 |
| A14        | V120–V129 |
| A15        | V130–V139 |
| A16        | V140–V149 |
| A17        | V150–V159 |
| A18        | V160–V169 |
| A19        | V170–V179 (reserved, no migrations) |
| —          | V180–V199 (reserved) |

**Phase 2** (docs/phase2/contracts.md) continues the scheme with one block
per new workstream:

| Workstream | Range     | Owner | Content |
|-----------:|-----------|-------|---------|
| ws20       | V200–V209 | SE1   | Settings: BankSetting, ConfigRevision |
| ws21       | V210–V219 | SE2   | Platform bank: Bank.Kind/Status, role trigger, Role CHECK widened |
| ws22       | V220–V229 | SE3   | Template.Visibility, dashboard indexes |
| ws23       | V230–V239 | SE4   | Extraction mode, field hints, parse provenance |
| ws24       | V240–V249 | SE5   | DeployedVersion, Announcement |
| ws25       | V250–V259 | SE4 (SE1: V251 BankEgressRule) | Integration core: V250 connections/flows/runs/outbox, V252 events + IngestionJob.Origin |
| ws26       | V260–V269 | SE2 (SE5: V261 IntegrationAgent) | Machine identity: V260 ApiClient/secrets/idempotency, V261 agents + connection FK (NOT VALID) |

Phase-2 migrations must also follow docs/release/migration-policy.md
(expand/contract: a release only adds; drops/renames/SET NOT NULL ship one
release later).

Files at `V20` and above are named `V{n}__ws{NN}_{desc}.sql` (e.g.
`V20__ws02_rbac_tables.sql`) so a glance at the folder shows who owns what.
