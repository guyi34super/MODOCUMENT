#!/usr/bin/env python3
"""Completeness check for the technology-risk documentation pack (docs/compliance).

Fails (exit 1) when any task document, control page, control data file,
questionnaire answer, evidence or test entry is missing, or when a page cites
a repo path that does not exist. Usage: python3 scripts/compliance/check_pack.py
"""
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "docs" / "compliance"
STATUSES = {"Fully Implemented", "Partially Implemented", "Not Implemented",
            "Not Applicable", "UNKNOWN - VALIDATION REQUIRED"}
RESULTS = {"Pass", "Partial", "Fail", "Not run"}
TOP_DIRS = ("src/", "frontend/", "docs/", "infra/", "e2e/", "perf/", "scripts/",
            "agent/", "connector-core/", ".github/", "deploy/", "keycloak/")
PATH_RE = re.compile(r"`([^`\s]+)`|\]\(([^)\s]+)\)")

problems = []


def problem(msg):
    problems.append(msg)


def cited_paths(md_file):
    for m in PATH_RE.finditer(md_file.read_text(encoding="utf-8")):
        raw = m.group(1) or m.group(2)
        if raw.startswith(("http://", "https://", "mailto:", "#")):
            continue
        target = raw.split("#")[0]
        target = re.sub(r":\d+(-\d+)?$", "", target)
        if m.group(2):  # Markdown link: relative to the page
            resolved = (md_file.parent / target).resolve()
            yield raw, resolved
        elif target.startswith(TOP_DIRS) and "..." not in target and "*" not in target \
                and "<" not in target and "{" not in target:
            yield raw, ROOT / target


def check_links(md_file):
    for raw, path in cited_paths(md_file):
        if not path.exists():
            problem(f"{md_file.relative_to(ROOT)}: cited path does not exist: {raw}")


def main():
    cw = yaml.safe_load((PACK / "crosswalk.yaml").read_text(encoding="utf-8"))
    counts = {"tasks": 0, "controls": 0, "answers": 0, "evidence": 0, "tests": 0}
    questions = sum(1 for c in cw["controls"] if c["question"])

    for t in cw["tasks"]:
        doc = ROOT / t["doc"]
        if not doc.exists():
            problem(f"{t['id']}: missing document {t['doc']}")
            continue
        if t["id"] not in doc.read_text(encoding="utf-8"):
            problem(f"{t['id']}: {t['doc']} never names its task ID")
            continue
        counts["tasks"] += 1

    for c in cw["controls"]:
        cid, page, data = c["id"], ROOT / c["page"], ROOT / c["data"]
        if not page.exists():
            problem(f"{cid}: missing page {c['page']}")
        else:
            text = page.read_text(encoding="utf-8")
            for needle in (f"{cid}-E01", f"{cid}-T01", f"R-{cid}"):
                if needle not in text:
                    problem(f"{cid}: page does not mention {needle}")
        if not data.exists():
            problem(f"{cid}: missing data file {c['data']}")
            continue
        d = yaml.safe_load(data.read_text(encoding="utf-8")) or {}
        if d.get("id") != cid:
            problem(f"{cid}: data file id is {d.get('id')!r}")
        if d.get("status") not in STATUSES:
            problem(f"{cid}: status {d.get('status')!r} is not a workbook status")
        else:
            counts["controls"] += 1
        for key in ("current_state", "gap", "required_action"):
            if not str(d.get(key) or "").strip():
                problem(f"{cid}: {key} is empty")
        ev = d.get("evidence") or {}
        if ev.get("id") == f"{cid}-E01" and str(ev.get("location") or "").strip():
            counts["evidence"] += 1
        else:
            problem(f"{cid}: evidence id/location missing")
        test = d.get("test") or {}
        if test.get("id") == f"{cid}-T01" and test.get("result") in RESULTS \
                and str(test.get("actual_result") or "").strip():
            counts["tests"] += 1
        else:
            problem(f"{cid}: test id/result/actual_result missing")
        if c["question"]:
            q = d.get("question") or {}
            if str(q.get("final_answer") or "").strip() and q.get("confidence") in {"High", "Medium", "Low"}:
                counts["answers"] += 1
            else:
                problem(f"{cid}: questionnaire final_answer/confidence missing")
        if c.get("policy_document") and not d.get("policy_document"):
            problem(f"{cid}: workbook asks for a policy/document ({c['policy_document']!r}) but none is linked")
        pol = d.get("policy_document")
        if pol and not (ROOT / pol.split("#")[0]).exists():
            problem(f"{cid}: policy_document path does not exist: {pol}")

    for md in sorted(PACK.rglob("*.md")):
        check_links(md)

    total = {"tasks": len(cw["tasks"]), "controls": len(cw["controls"]), "answers": questions,
             "evidence": len(cw["controls"]), "tests": len(cw["controls"])}
    print("  ".join(f"{k} {counts[k]}/{total[k]}" for k in total))
    for p in problems:
        print("  -", p)
    print(f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
