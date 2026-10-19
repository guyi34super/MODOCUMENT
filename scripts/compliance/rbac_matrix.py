#!/usr/bin/env python3
"""RBAC matrix and route inventory for MO-018 / MO-019 / SEC-011 (agent A4).

Reads only the repository:
  * docs/security/role-permission-matrix.csv - byte-pinned to
    src/main/java/com/modocument/application/security/RolePermissions.java by
    RolePermissionMatrixTest, so it IS the code's role x permission matrix;
  * every @RestController under src/main/java - each handler's HTTP verb, path
    and its @RequiresPermission (method level, else class level).

Writes:
  * docs/compliance/evidence/A4-route-permission-inventory.md  (every route)
  * the generated blocks between the GENERATED markers in
    docs/compliance/mo-018-rbac-role-permission-matrix.md

Usage: python3 scripts/compliance/rbac_matrix.py [--check]
  --check  exit 1 if the generated files are not up to date (writes nothing).

Static parsing: the authoritative runtime check is RoutePermissionCoverageTest
(every mapped handler carries exactly one @RequiresPermission or is on its
public allowlist). This script prints the same inventory for reviewers.
"""
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CSV = ROOT / "docs/security/role-permission-matrix.csv"
SRC = ROOT / "src/main/java"
MO018 = ROOT / "docs/compliance/mo-018-rbac-role-permission-matrix.md"
INVENTORY = ROOT / "docs/compliance/evidence/A4-route-permission-inventory.md"

# Business capability (the checklist's wording) -> permissions, in display order.
CAPABILITIES = [
    ("View / edit templates", ["TEMPLATE_READ", "TEMPLATE_WRITE", "TEMPLATE_PUBLISH",
                               "VALIDATION_READ", "VALIDATION_WRITE"]),
    ("Enter data (upload, ingestion, extraction)", [
        "FILE_UPLOAD", "INGESTION_READ", "INGESTION_WRITE", "IMPORT_READ", "IMPORT_WRITE",
        "EXTRACTION_READ", "EXTRACTION_WRITE", "EXTRACTION_RUN", "AI_USE"]),
    ("Generate / download reports and exports", [
        "DOC_READ", "DOC_WRITE", "DOC_GENERATE", "DOC_RELEASE", "EXPORT_RUN"]),
    ("Manage users and approve changes", ["USER_ADMIN", "CHANGE_APPROVE"]),
    ("View logs and monitoring", ["AUDIT_READ", "SIEM_READ", "METRICS_READ"]),
    ("Bank settings and integrations", ["SETTINGS_READ", "SETTINGS_WRITE",
                                        "INTEGRATION_ADMIN", "INTEGRATION_RUN"]),
    ("Shared reference data", ["LOOKUP_READ", "LOOKUP_ADMIN"]),
    ("Self-service and system information", ["PROFILE_SELF", "SYSTEM_INFO"]),
    ("Platform owner (MO Intelligence)", ["PLATFORM_READ", "PLATFORM_BANKS",
                                          "PLATFORM_SETTINGS", "PLATFORM_ANNOUNCE"]),
    ("Machine integration (API client scopes)", [
        "INTEGRATION_INGEST", "INTEGRATION_RESULTS", "INTEGRATION_DOCS",
        "INTEGRATION_EVENTS", "AGENT_CONNECT"]),
]

# Personal-data note per permission. Source of each statement:
# docs/security/masking-matrix.md (MaskingPolicy, PiiMaskingService),
# TemplateOwnership / IngestionJobUseCase (owner scoping), DataSubjectController.
PII = {
    "TEMPLATE_READ": "No (field definitions only)",
    "TEMPLATE_WRITE": "No (field definitions; sets the PII tag of fields)",
    "TEMPLATE_PUBLISH": "No",
    "VALIDATION_READ": "Indirect: runs rules on rows the caller supplies",
    "VALIDATION_WRITE": "No",
    "FILE_UPLOAD": "Yes: uploads source files that may hold client PII",
    "INGESTION_READ": "Yes: own jobs, plus the records of bank-shared templates; raw for ADMIN/APPROVER/READ_WRITE (SCREEN_WORKING), masked for READ_ONLY",
    "INGESTION_WRITE": "Yes, own jobs only: edits raw values",
    "IMPORT_READ": "Yes, own import history (raw checkpoints)",
    "IMPORT_WRITE": "Yes, own import history",
    "EXTRACTION_READ": "Yes: own extraction jobs and their records",
    "EXTRACTION_WRITE": "Indirect: configures sources",
    "EXTRACTION_RUN": "Yes: pulls records from bank sources",
    "AI_USE": "Tokenized: PII is replaced by tokens before an LLM call (PiiTokenizer)",
    "DOC_READ": "Yes, own documents; report text masked on render (REPORT)",
    "DOC_WRITE": "Yes, own document content blocks (raw, working screen)",
    "DOC_GENERATE": "Masked: NIC/account/email shapes masked in generated output",
    "DOC_RELEASE": "Yes: release queue shows the submitted content of the bank",
    "EXPORT_RUN": "Masked: every export masked for every role (EXPORT)",
    "USER_ADMIN": "Yes: staff user data, and DSR search/export of raw client PII in own bank",
    "CHANGE_APPROVE": "Staff data in pending changes (no credential material)",
    "AUDIT_READ": "Staff identities in the audit trail (and CSV export)",
    "SIEM_READ": "Staff identities in the audit feed",
    "METRICS_READ": "No (counts)",
    "SETTINGS_READ": "No",
    "SETTINGS_WRITE": "No",
    "INTEGRATION_ADMIN": "Yes: may configure an outbound flow with masking off (maker/checker)",
    "INTEGRATION_RUN": "Yes: runs flows that move bank data",
    "LOOKUP_READ": "No",
    "LOOKUP_ADMIN": "No",
    "PROFILE_SELF": "Own account only",
    "SYSTEM_INFO": "No",
    "PLATFORM_READ": "No: cross-bank counts only",
    "PLATFORM_BANKS": "Staff data of new bank users (onboarding, break-glass)",
    "PLATFORM_SETTINGS": "No",
    "PLATFORM_ANNOUNCE": "No",
    "INTEGRATION_INGEST": "Yes: sends records into its bank",
    "INTEGRATION_RESULTS": "Masked: validated rows masked (EXPORT channel)",
    "INTEGRATION_DOCS": "Masked: released documents",
    "INTEGRATION_EVENTS": "No (event metadata)",
    "AGENT_CONNECT": "Yes: the on-prem agent relays bank data",
}

MAPPING = re.compile(r"@(Get|Post|Put|Delete|Patch|Request)Mapping\b")
PERM = re.compile(r"@RequiresPermission\(\s*Permission\.([A-Z_]+)\s*\)")
CONST = re.compile(r'static\s+final\s+String\s+([A-Z_][A-Z0-9_]*)\s*=\s*([^;]+);')
SIGNATURE = re.compile(r"^\s*(public|protected|private)?\s*[\w<>\[\]?,. ]+\s+\w+\s*\(")


def read_matrix():
    with CSV.open(newline="", encoding="utf-8") as fh:
        rows = list(csv.reader(fh))
    roles = rows[0][2:]
    matrix = {r[0]: {"code": r[1], "roles": {role for role, cell in zip(roles, r[2:]) if cell == "x"}}
              for r in rows[1:]}
    return roles, matrix


def eval_expr(expr, consts):
    """Evaluates a Java string expression of literals, constants and '+'."""
    out = []
    for part in re.split(r"\s*\+\s*", expr.strip()):
        part = part.strip()
        if part.startswith('"') and part.endswith('"'):
            out.append(part[1:-1])
        elif part in consts:
            out.append(consts[part])
        else:
            return None
    return "".join(out)


def annotation_text(lines, i):
    """The full text of the (possibly multi-line) annotation starting at line i."""
    text, depth = "", 0
    for line in lines[i:]:
        text += line.strip() + " "
        depth += line.count("(") - line.count(")")
        if depth <= 0 and "(" in text or ("(" not in text and line.strip().startswith("@")):
            if depth <= 0:
                break
    return text


def paths_of(ann, consts):
    m = re.search(r"\((.*)\)", ann)
    if not m:
        return [""]
    args = m.group(1)
    vm = re.search(r"(?:value|path)\s*=\s*(\{[^}]*\}|[^,]+)", args)
    expr = vm.group(1) if vm else args.split(",")[0] if not args.strip().startswith("{") else args
    expr = expr.strip()
    if expr.startswith("{"):
        parts = [p for p in re.split(r",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", expr.strip("{} ")) if p.strip()]
    else:
        parts = [expr]
    if "=" in expr and not vm:
        return [""]
    return [eval_expr(p, consts) or f"<{p.strip()}>" for p in parts]


def routes():
    found = []
    for path in sorted(SRC.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        if "@RestController" not in text and "@Controller" not in text:
            continue
        lines = text.splitlines()
        consts = {}
        for name, expr in CONST.findall(text):
            val = eval_expr(expr, consts)
            if val is not None:
                consts[name] = val
        class_idx = next(i for i, l in enumerate(lines) if re.search(r"\bclass\s+\w+", l)
                         and not l.strip().startswith(("*", "/")))
        header = "\n".join(lines[:class_idx + 1])
        class_perm = PERM.search(header)
        prefix = ""
        for j, l in enumerate(lines[:class_idx]):
            if "@RequestMapping" in l:
                prefix = paths_of(annotation_text(lines, j), consts)[0]
        for i, line in enumerate(lines[class_idx + 1:], start=class_idx + 1):
            m = MAPPING.search(line)
            if not m or line.strip().startswith(("*", "//")):
                continue
            verb = m.group(1).upper()
            ann = annotation_text(lines, i)
            # annotation block: back to the previous member end, forward to the signature
            start = i
            while start > class_idx + 1 and not lines[start - 1].rstrip().endswith((";", "}", "{")):
                start -= 1
            end = i
            while end < len(lines) and not (SIGNATURE.match(lines[end]) and not lines[end].strip().startswith("@")):
                end += 1
            block = "\n".join(lines[start:end + 1])
            perm = PERM.search(block)
            permission = perm.group(1) if perm else (class_perm.group(1) if class_perm else "PUBLIC")
            method = re.search(r"(\w+)\s*\(", lines[end]).group(1) if end < len(lines) else "?"
            for p in paths_of(ann, consts):
                found.append({"verb": verb, "path": prefix + p, "permission": permission,
                              "handler": f"{path.stem}#{method}",
                              "file": str(path.relative_to(ROOT))})
    return found


def render_matrix(roles, matrix):
    out = ["| Capability | Permission | Code | " + " | ".join(f"`{r}`" for r in roles)
           + " | Personal data |",
           "|---|---|---|" + "---|" * len(roles) + "---|"]
    listed = set()
    for cap, perms in CAPABILITIES:
        for k, p in enumerate(perms):
            listed.add(p)
            cells = ["x" if r in matrix[p]["roles"] else "" for r in roles]
            out.append(f"| {cap if k == 0 else ''} | `{p}` | `{matrix[p]['code']}` | "
                       + " | ".join(cells) + f" | {PII[p]} |")
    missing = set(matrix) - listed
    if missing:
        raise SystemExit(f"permissions not placed in a capability: {sorted(missing)}")
    totals = [str(sum(1 for p in matrix if r in matrix[p]["roles"])) for r in roles]
    out.append(f"| **Total** | {len(matrix)} permissions | | " + " | ".join(totals) + " | |")
    return "\n".join(out)


def render_route_summary(roles, matrix, found):
    by_perm = defaultdict(list)
    for r in found:
        by_perm[r["permission"]].append(r)
    out = ["| Permission | Routes | Roles holding it | Example route |", "|---|---|---|---|"]
    for p in matrix:
        rs = by_perm.get(p, [])
        ex = f"`{rs[0]['verb']} {rs[0]['path']}`" if rs else "none"
        out.append(f"| `{p}` | {len(rs)} | {', '.join(r for r in roles if r in matrix[p]['roles'])} | {ex} |")
    pub = by_perm.get("PUBLIC", [])
    out.append(f"| (public allowlist) | {len(pub)} | anonymous | "
               + ", ".join(f"`{r['verb']} {r['path']}`" for r in pub) + " |")
    out.append(f"| **Total** | {len(found)} | | |")
    return "\n".join(out)


def render_inventory(found):
    out = ["# A4 evidence: route to permission inventory",
           "",
           "Generated by `scripts/compliance/rbac_matrix.py` from the controllers under "
           "`src/main/java` (static parse). The runtime proof that no handler is "
           "unguarded is `src/test/java/com/modocument/api/security/RoutePermissionCoverageTest.java`.",
           "Command: `python3 scripts/compliance/rbac_matrix.py`. Run for this pack on 2026-10-19.",
           "",
           f"{len(found)} handler routes; "
           f"{sum(1 for r in found if r['permission'] == 'PUBLIC')} on the public allowlist.",
           "",
           "| # | Verb | Path | Permission | Handler |", "|---|---|---|---|---|"]
    for n, r in enumerate(sorted(found, key=lambda r: (r["path"], r["verb"])), 1):
        out.append(f"| {n} | {r['verb']} | `{r['path']}` | `{r['permission']}` | {r['handler']} |")
    return "\n".join(out) + "\n"


def replace_block(text, name, body):
    begin, end = f"<!-- BEGIN GENERATED {name} -->", f"<!-- END GENERATED {name} -->"
    pattern = re.compile(re.escape(begin) + r".*?" + re.escape(end), re.S)
    if not pattern.search(text):
        raise SystemExit(f"{MO018.name}: markers for {name} not found")
    return pattern.sub(lambda _: f"{begin}\n{body}\n{end}", text)


def main():
    check = "--check" in sys.argv
    roles, matrix = read_matrix()
    found = routes()
    unknown = {r["permission"] for r in found} - set(matrix) - {"PUBLIC"}
    if unknown:
        raise SystemExit(f"routes reference unknown permissions: {unknown}")
    outputs = {INVENTORY: render_inventory(found)}
    if MO018.exists():
        text = MO018.read_text(encoding="utf-8")
        text = replace_block(text, "MATRIX", render_matrix(roles, matrix))
        text = replace_block(text, "ROUTES", render_route_summary(roles, matrix, found))
        outputs[MO018] = text
    stale = [p for p, body in outputs.items() if not p.exists() or p.read_text(encoding="utf-8") != body]
    if check:
        for p in stale:
            print(f"stale: {p.relative_to(ROOT)}")
        return 1 if stale else 0
    for p, body in outputs.items():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")
    print(f"{len(found)} routes, {len(matrix)} permissions, {len(roles)} roles; wrote "
          + ", ".join(str(p.relative_to(ROOT)) for p in outputs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
