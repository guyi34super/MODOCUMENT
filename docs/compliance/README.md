# MoDocument — technology-risk documentation pack

This folder answers the MO Intelligence technology-risk review:

- **Bhavish's work checklist:** tasks MO-001, MO-009, MO-016…019 and MO-031…034.
- **Three technology-risk workbooks:** Security (02), Software Engineering (03) and IT Infrastructure & Operations (04), with 57 controls in total.

Every statement is written from this repository. Each one is either backed by a file, test, workflow, migration or Terraform module that a reviewer can open, or marked clearly as something the repository cannot prove.

`crosswalk.yaml` lists every task and control, the page that answers it, and its structured data file. `scripts/compliance/check_pack.py` checks that nothing is missing.

## Layout

| Path | What it holds |
|---|---|
| `00-index.md` | Pack index: every task, control, questionnaire answer and evidence ID → document and section |
| `mo-0xx-*.md` | The ten checklist deliverables |
| `controls/<CID>.md` | One evidence page per control (57) |
| `controls/<CID>.yaml` | The same control as structured data; feeds the updated workbooks |
| `policies/*.md` | Policies and procedures the workbooks name that did not exist yet. Existing ones stay in `docs/security/`, `docs/release/` and `docs/integration/`, and are linked. |
| `evidence/` | Captured outputs of runs done for this pack (test summaries, scan results, probe tables), each with its command and date |
| `crosswalk.yaml` | Generated ID list; do not edit by hand |

## Writing rules (all authors)

1. **Proof or a label.** Each claim about the system names its proof in backticks as a repo-relative path, e.g. `src/main/java/.../RolePermissions.java` or `.github/workflows/ci.yml`. A test class can be cited by name, as long as its file exists. If the repository cannot prove a claim, use one of these markers:
   - **`TO CONFIRM (organisational)`**: a fact only the company can supply, such as board members, the DPO's reporting line, a contract or SLA clause, an MDM or e-mail domain setting, physical premises, or an external pen-test vendor.
   - **`NOT IN REPO (live cloud)`**: evidence that only exists once AWS is deployed, such as CloudTrail, GuardDuty, a live failover, object-lock, real uptime, or a production restore.
   
   Never invent a fact, a figure, a date or a name.
2. **Numbers come from a run.** Any figure (latency, count, test total) must come from a run made for this pack, with its command and date in `evidence/`, or from an existing results file, cited by path.
3. **IDs are the workbook IDs:**
   - controls: `PRIV-002`, `SEC-011`, `SUP-S12`;
   - evidence: `<CID>-E01`;
   - tests: `<CID>-T01`;
   - risks: `R-<CID>`;
   - tasks: `MO-0xx`.
   
   Role names are the code's (`Role` enum, `docs/security/role-catalogue-and-sod.md`), and people's roles follow the RACI in `mo-001-governance-structure.md`.
4. **People:** use only the names already in the workbooks: Bhavish Nobeen, Rushal, Reezvi Pydiah, Taj Aundoo, "the DPO" and the Board of Directors. Anything else about a person is `TO CONFIRM (organisational)`.
5. **No personal or client data.** Tests use synthetic data only. Do not paste secrets, keys, tokens or real hostnames.
6. **Plain, exact English.** Short sentences, present tense for what exists today, future tense only with an owner and a date. Tables are welcome.
7. **Today's date for this pack is 2026-10-19.** A workbook target date that has already passed is reported honestly as overdue, with a new proposed date marked `proposed`.

## Control page template (`controls/<CID>.md`)

```markdown
# <CID> — <Control name>

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|

## Requirement
<the workbook's requirement, in one or two sentences>

## How MoDocument meets it today
<what the code/config/docs do, each line with its proof path>

## Questionnaire answer (final)        ← only for controls in the questionnaire
<answer a reviewer can send as-is; names its evidence ID>

## Evidence <CID>-E01
| Item | Location | Kind (repo / run / to confirm) |

## Test <CID>-T01
Procedure · expected result · actual result · Pass / Partial / Fail / Not run · tester · date

## Gap and remediation
Gap, gap type (Build / Doc / Config / Org / None), action, owner, target date, roadmap phase (Day 30/60/90)

## Risk R-<CID>
Risk statement, cause and mitigation. Likelihood, impact and residual are set in MO-031.

## To confirm
<bulleted TO CONFIRM / NOT IN REPO items, or "None">
```

## Structured data (`controls/<CID>.yaml`)

The workbook updater reads these keys. Text values are plain text with no Markdown.

```yaml
id: SEC-011
status: Partially Implemented   # Fully Implemented | Partially Implemented | Not Implemented | Not Applicable | UNKNOWN - VALIDATION REQUIRED
current_state: "..."            # repo-proven summary (Gap Assessment "Current State")
gap: "..."                      # "None" when nothing remains
gap_type: Build                 # Build | Doc | Config | Org | None
required_action: "..."
action_owner: "Security — Bhavish Nobeen"
target_date: 2026-11-13         # ISO date or null
evidence:
  id: SEC-011-E01
  status: Complete              # Complete | Partial — to collect | Missing
  location: "docs/compliance/controls/SEC-011.md; src/test/java/.../RoutePermissionCoverageTest.java"
question:                       # omit when the control has no questionnaire row
  final_answer: "..."
  confidence: High              # High | Medium | Low
test:
  id: SEC-011-T01
  actual_result: "..."
  result: Pass                  # Pass | Partial | Fail | Not run
  tester: "Agent A4 (automated re-performance); human sign-off required"
  date: 2026-10-19
roadmap:
  phase: Day 30                 # Day 30 | Day 60 | Day 90 | Done
  action: "..."
  status: Done                  # Done | In progress | Not started
risk:                           # filled by A9 (MO-031)
  likelihood: null              # 1–5
  impact: null                  # 1–5
  residual: null                # Low | Medium | High | Critical
policy_document: null           # path of the named policy/document, when the workbook asks for one
to_confirm: []                  # list of strings
```
