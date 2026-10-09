# Security policy — MoDocument (SUP-S12)

## Reporting a vulnerability

Email **security@modocument.example** (machine-readable copy:
[`/.well-known/security.txt`](frontend/public/.well-known/security.txt),
served by the frontend). Please include steps to reproduce and the affected
endpoint; do not include real customer data in reports.

> Mailbox activation is go-live gate **E5** (owner: Taj Aundoo). Until it is
> live, report privately through GitHub's private vulnerability reporting on
> this repository (Security tab → "Report a vulnerability") or directly to the
> repository owner (@guyi34super) — do not open a public issue for a
> vulnerability.

What to expect: acknowledgement within 2 business days, a triage decision
within 7, and coordinated disclosure — we ask that you give us 90 days
before publishing. Good-faith research against **your own data on the
staging environment** is welcome; never test against production banks' data,
never run denial-of-service, and respect the extraction compliance rules the
product itself enforces.

## Scope

The application in this repository (backend API, React frontend, Docker
images) and, once applied, the infrastructure under `infra/terraform/`.
Out of scope: the banks' own identity providers, third-party LLM endpoints,
and social engineering.

## Supported versions

The `main` branch and the latest tagged release receive security fixes.

## Where the security documentation lives

* Control inventory: [`docs/security/security-feature-catalogue.md`](docs/security/security-feature-catalogue.md)
* Threats × controls: [`docs/security/threat-control-matrix.md`](docs/security/threat-control-matrix.md)
* Crypto standard: [`docs/security/crypto-standard.md`](docs/security/crypto-standard.md)
* Branch protection and CI security scans: [`docs/security/branch-protection.md`](docs/security/branch-protection.md)
* Incident response: [`docs/security/incident-response-stub.md`](docs/security/incident-response-stub.md)
* Open items & owners: [`docs/security/security-roadmap.md`](docs/security/security-roadmap.md) and the README section *"Implement later (cloud & organisational)"*
