# SUP-S04 — Container / Kubernetes hardening (if containerised)

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 04 IT Infrastructure | IT Infrastructure & Operations | P2 | Partially Implemented | SUP-S04-E01 | SUP-S04-T01 | R-SUP-S04 |

## Requirement
Apply the container-specific baseline; skip this card only if the
service runs on plain VMs. Acceptance (workbook): an unsigned or
root-running image is rejected by the cluster in a test.

## Applicability
The workbook marks this control **Not Applicable** (decision of 2 Oct
2026: ECS on EC2, no Kubernetes), pending pack 07 approval. A8's view from
the repository: **Kubernetes-specific items do not apply** (no cluster,
no pods, no admission controller, no kube-bench), but MoDocument **is
containerised** (ECS tasks), and the card's own rule says to skip it only
for plain VMs. The container items therefore apply, and the status is set
by the evidence below: **Partially Implemented**. Pack 07 should decide on
this basis. `TO CONFIRM (organisational)`: pack 07 approval.

## How MoDocument meets it today
| Container baseline item | State | Proof |
|---|---|---|
| Non-root images | App image `USER 10001:10001`; frontend `nginxinc/nginx-unprivileged` (`USER 101`); agent distroless `nonroot` (`USER 65532`) | `Dockerfile`; `frontend/Dockerfile`; `agent/Dockerfile` |
| Non-root enforced at run time | Task definition sets `user` for both containers, so a root-built image still runs as 101 / 10001 | `infra/terraform/modules/ecs-app/main.tf` (TF-68) |
| Capabilities and privilege escalation | All Linux capabilities dropped; `no-new-privileges`; privileged containers disabled on the nodes (`ECS_DISABLE_PRIVILEGED=true`) | TF-68, TF-61; `docker-compose.yml` mirrors it (`cap_drop: [ALL]`, `no-new-privileges`) |
| Read-only root filesystem | **No**: frontend explicitly writable (its entrypoint renders nginx config at start); app not set | TF-68 |
| Minimal base | Agent: distroless. App: `eclipse-temurin:21-jre` (full JRE plus LibreOffice/Tesseract); frontend: Alpine | the three Dockerfiles |
| Base image pinning | Base images pinned by **tag**, not digest; Dependabot proposes updates weekly. Released images are pinned **by digest** in the ECS task definition | `.github/dependabot.yml`; `.github/workflows/release.yml`; `infra/terraform/README.md` "Releases and deployments" |
| Image scanning | Trivy in CI on every release (blocks fixable HIGH/CRITICAL) and weekly; ECR scan on push | `.github/workflows/release.yml`; `.github/workflows/image-rebuild.yml`; `infra/terraform/modules/ci-oidc/main.tf` (TF-101) |
| Immutable tags | ECR repositories `IMMUTABLE` | TF-101 |
| SBOM | Generated per release | `.github/workflows/sbom.yml`; `.github/workflows/release.yml` |
| Image signing and verification | **None** (no cosign or signature check; ECS has no admission control) | search of `.github/workflows/` |
| Per-task least privilege | Execution role reads exactly the injected secrets; task role reaches only the file-store bucket and key | TF-65, TF-66 |
| Resource limits | CPU and hard memory limits per container | TF-68 (`app_cpu`, `app_memory`, `frontend_cpu`, `frontend_memory` in `infra/terraform/modules/ecs-app/variables.tf`) |
| Host hardening | IMDSv2 only, no SSH key, encrypted root volume, Session Manager only | TF-61, TF-64 |
| Weekly rebuild | Yes, but the 2026-10-19 run did not start (GitHub billing), and the 12 Oct findings are unpatched (SEC-026) | [A8-patch-report-2026-10.md](../evidence/A8-patch-report-2026-10.md) |

## Evidence SUP-S04-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Dockerfiles (non-root) | `Dockerfile`; `frontend/Dockerfile`; `agent/Dockerfile` | repo |
| Task definition hardening | `infra/terraform/modules/ecs-app/main.tf` | repo |
| Scanning workflows | `.github/workflows/release.yml`; `.github/workflows/image-rebuild.yml` | repo |
| Latest weekly scan result | GitHub issue #52 (see [A8-patch-report-2026-10.md](../evidence/A8-patch-report-2026-10.md)) | run (2026-10-12, read 2026-10-19) |
| Admission policies, kube-bench report | — | Not applicable (no Kubernetes) |
| Signed-image verification log | — | Not available: no signing exists |
| Running tasks' user and capabilities | ECS console / `aws ecs describe-tasks` | `NOT IN REPO (live cloud)` |

## Test SUP-S04-T01
- **Procedure:** read the three Dockerfiles and the task definition for
  user, capabilities, privilege flags and filesystem; check CI for
  scanning and signing; acceptance adapted to ECS: try to run a root-user
  or unsigned image as the service.
- **Expected result:** an unsigned or root-running image is rejected.
- **Actual result:** a root-built image would still run as the non-root
  user set in the task definition (so it does not run as root), but
  nothing rejects an **unsigned** image: signing does not exist. Read-only
  root filesystem not used. Live check not possible (no AWS).
- **Result:** Partial.
- **Tester:** Agent A8 (static re-performance); human sign-off required.
- **Date:** 2026-10-19.

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| N/A decision pending | Org | Pack 07 decides: K8s items N/A, container items in scope | Pack 07 reviewer | `TO CONFIRM (organisational)` | Day 30 |
| No image signing / verification | Build | Sign release images (cosign or AWS Signer) and verify the digest before deploy in `deploy.yml` | Bhavish Nobeen | `TO CONFIRM (organisational)` | Day 90 |
| Writable root filesystems | Build | `readonlyRootFilesystem = true` with tmpfs/volumes for nginx config and app temp | Bhavish Nobeen | `TO CONFIRM (organisational)` | Day 90 |
| Base images by tag | Build | Pin base images by digest (Dependabot keeps them current) | Bhavish Nobeen | `TO CONFIRM (organisational)` | Day 60 |
| Open critical image findings | Build | See SEC-026 | Bhavish Nobeen | 2026-10-22 (proposed) | Day 30 |

## Risk R-SUP-S04
- **Risk:** one compromised container pivots to the host or other tasks.
- **Cause:** images run as root with no policies (workbook cause; in this
  repository images do not run as root, but there is no signing and the
  filesystem is writable).
- **Mitigation:** non-root, capability drop, no-new-privileges, IMDSv2 hop
  limit, least-privilege task roles, scanning; add signing and read-only
  filesystems. Likelihood, impact and residual are set in MO-031.

## To confirm
- `NOT IN REPO (live cloud)`: running tasks' effective user and capabilities.
- `TO CONFIRM (organisational)`: pack 07 approval of the applicability decision.
