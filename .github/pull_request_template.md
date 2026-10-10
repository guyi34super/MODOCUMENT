# Change summary

<!-- What does this PR change, and why? Link the issue/workstream item. -->

# Security checklist

<!-- Tick what you verified; strike through with a one-line reason when not
     applicable. Reviewers: treat an unticked, unexplained box as a blocker. -->

- [ ] **Authorization on new/changed routes** — every new endpoint is mapped
      in the route-permission model; `RoutePermissionCoverageTest` passes and
      was extended for the new routes (it fails on unmapped routes — do not
      broaden its exclusions).
- [ ] **Tenant scoping** — new queries/repositories filter by tenant (RLS
      policies cover new tables; `-Ppostgres-it` tenant-isolation tests
      updated where schema changed).
- [ ] **Audit events** — security-relevant actions added here (auth, roles,
      exports, template/user changes) emit audit events.
- [ ] **PII handling / masking** — log statements for new data go through the
      redaction layer; masking matrix (docs/security/masking-matrix.md)
      updated if display rules changed.
- [ ] **Migrations in correct range** — new Flyway migrations use this
      workstream's reserved version range and never edit an applied migration.
- [ ] **No secrets** — no credentials, tokens or keys in code, config, tests
      or fixtures (gitleaks runs on every PR; do not add allowlist entries
      without a documented justification in `.gitleaks.toml`).

# Privacy checklist (PRIV-004)

- [ ] **New fields classified** — any new stored field holding personal data
      is classified for PII and added to the masking matrix.
- [ ] **Retention category assigned** — new data has a retention category
      (docs/security/retention-and-dsr.md); purge/retention jobs cover it.
- [ ] **DSR impact assessed** — data-subject access/erasure flows account for
      the new data (export includes it; erasure/anonymization reaches it).

<!-- CI gates on this PR: backend, backend-postgres, frontend, gitleaks,
     semgrep, dependency-review, npm-audit. A second person (CODEOWNERS) must
     approve before merge. -->
