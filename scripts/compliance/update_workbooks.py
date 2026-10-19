#!/usr/bin/env python3
"""Write repo-verified answers from docs/compliance into copies of the risk workbooks.

Usage:
  python3 scripts/compliance/update_workbooks.py --checklist <Bhavish.xlsx> \
      --workbook 02-security=<02 file> --workbook 03-software-engineering=<03 file> \
      --workbook 04-it-infrastructure=<04 file> --out <dir>

The originals are never modified; each copy is saved as <name>_updated.xlsx.
Formatting, dropdowns and the Dashboard formulas are kept (recalculate the copy in
Excel or LibreOffice to refresh the cached Dashboard values).
"""
import argparse
import datetime as dt
from pathlib import Path

import openpyxl
import yaml

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "docs" / "compliance"
TODAY = dt.date(2026, 10, 19)
REVIEW_STATUS = "Answer drafted from the repository on 2026-10-19 — awaiting pack 07 review"


def load_controls():
    out = {}
    for f in sorted((PACK / "controls").glob("*.yaml")):
        d = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        if d.get("id"):
            out[d["id"]] = d
    return out


def load_risks():
    f = PACK / "risks.yaml"
    if not f.exists():
        return {}
    data = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    return {r["id"]: r for r in data.get("risks", [])}


def as_date(value):
    if value in (None, ""):
        return None
    if isinstance(value, dt.date):
        return dt.datetime(value.year, value.month, value.day)
    try:
        d = dt.date.fromisoformat(str(value)[:10])
        return dt.datetime(d.year, d.month, d.day)
    except ValueError:
        return None


def closed(c):
    return c.get("status") in ("Fully Implemented", "Not Applicable") or c.get("gap_type") == "None"


def header_index(ws):
    return {str(c.value).strip(): i for i, c in enumerate(ws[1]) if c.value}


def rows(ws, key_col):
    h = header_index(ws)
    k = h[key_col]
    for r in ws.iter_rows(min_row=2):
        v = r[k].value
        if v not in (None, ""):
            yield h, r, str(v).strip()


def put(row, h, col, value):
    if col in h and value not in (None,):
        row[h[col]].value = value


def score_label(n, labels=("1 — Rare", "2 — Unlikely", "3 — Possible", "4 — Likely", "5 — Almost certain")):
    return labels[int(n) - 1] if n else None


IMPACT = ("1 — Insignificant", "2 — Minor", "3 — Moderate", "4 — Major", "5 — Severe")


def update_risk_workbook(path, controls, risks, out_dir):
    wb = openpyxl.load_workbook(path)
    seen = set()

    for h, r, cid in rows(wb["Control Register"], "Control ID"):
        c = controls.get(cid)
        if not c:
            continue
        seen.add(cid)
        ev = c.get("evidence") or {}
        put(r, h, "Current Status", c["status"])
        put(r, h, "Gap", c.get("gap"))
        put(r, h, "Required Action", c.get("required_action"))
        put(r, h, "Evidence Available", "Yes" if ev.get("status") == "Complete" else "No")
        if as_date(c.get("target_date")):
            put(r, h, "Target Date", as_date(c.get("target_date")))
        if closed(c):
            put(r, h, "Actual Date", as_date((c.get("test") or {}).get("date")) or as_date(TODAY))
        put(r, h, "Status", "Closed" if closed(c) else "Open")
        old = r[h["Comments"]].value if "Comments" in h else None
        note = f"Repo-verified 2026-10-19: {c.get('current_state')} See docs/compliance/controls/{cid}.md."
        if old:
            note += f" | Owner statement 2 Oct 2026: {old}"
        put(r, h, "Comments", note)

    for h, r, cid in rows(wb["Questionnaire Answers"], "Question ID"):
        q = (controls.get(cid) or {}).get("question") or {}
        if not q:
            continue
        put(r, h, "Evidence ID", f"{cid}-E01")
        put(r, h, "Confidence", q.get("confidence"))
        put(r, h, "Review Status", REVIEW_STATUS)
        put(r, h, "Final Answer", q.get("final_answer"))

    for h, r, cid in rows(wb["Gap Assessment"], "Control"):
        c = controls.get(cid)
        if not c:
            continue
        put(r, h, "Current State", c.get("current_state"))
        put(r, h, "Gap (common)", c.get("gap"))
        put(r, h, "Gap Type", c.get("gap_type"))
        put(r, h, "Remediation", c.get("required_action"))
        put(r, h, "Owner", c.get("action_owner"))
        if as_date(c.get("target_date")):
            put(r, h, "Target Date", as_date(c.get("target_date")))
        put(r, h, "Status", "Closed" if closed(c) else "Open")

    for h, r, cid in rows(wb["Implementation Plan"], "Control ID"):
        c = controls.get(cid)
        if not c:
            continue
        rm = c.get("roadmap") or {}
        put(r, h, "Status", rm.get("status"))
        if as_date(c.get("target_date")):
            put(r, h, "End", as_date(c.get("target_date")))
        put(r, h, "Evidence", f"{cid}-E01")

    for h, r, cid in rows(wb["Evidence Register"], "Control ID"):
        ev = (controls.get(cid) or {}).get("evidence") or {}
        if not ev:
            continue
        put(r, h, "Location", ev.get("location"))
        put(r, h, "Review Date", as_date(TODAY))
        put(r, h, "Status", ev.get("status"))

    for h, r, cid in rows(wb["Testing"], "Control ID"):
        c = controls.get(cid)
        t = (c or {}).get("test") or {}
        if not t:
            continue
        put(r, h, "Actual Result", t.get("actual_result"))
        put(r, h, "Pass/Fail", t.get("result"))
        put(r, h, "Tester", t.get("tester"))
        put(r, h, "Test Date", as_date(t.get("date")))
        if t.get("result") != "Pass":
            put(r, h, "Finding", c.get("gap"))
            put(r, h, "Remediation", c.get("required_action"))

    for h, r, rid in rows(wb["Risk Register"], "Risk ID"):
        rk = risks.get(rid)
        if not rk:
            cid = rid[2:] if rid.startswith("R-") else None
            rk = (controls.get(cid) or {}).get("risk") if cid else None
        if not rk or not rk.get("likelihood"):
            continue
        put(r, h, "Likelihood", score_label(rk.get("likelihood")))
        put(r, h, "Impact", score_label(rk.get("impact"), IMPACT))
        put(r, h, "Residual Risk", rk.get("residual"))
        put(r, h, "Status", rk.get("status"))
        put(r, h, "Mitigation", rk.get("mitigation"))
        if rk.get("acceptance_required") is not None:
            put(r, h, "Acceptance Required", "Yes" if rk.get("acceptance_required") else "No")

    for h, r, cid in rows(wb["30-60-90 Roadmap"], "Control"):
        c = controls.get(cid)
        if not c:
            continue
        rm = c.get("roadmap") or {}
        put(r, h, "Status", rm.get("status"))
        if as_date(c.get("target_date")):
            put(r, h, "Target Date", as_date(c.get("target_date")))

    for h, r, cid in rows(wb["Policies & Documents"], "Control"):
        c = controls.get(cid)
        if not c or not c.get("policy_document"):
            continue
        put(r, h, "Status", "Draft v1 written 2026-10-19 — awaiting approval")
        put(r, h, "Evidence Location", c["policy_document"])

    readme = wb["README"]
    readme.cell(row=readme.max_row + 1, column=1).value = (
        "Update 19 Oct 2026: statuses, gaps, final answers, evidence locations and test results re-verified "
        "against the MoDocument repository (docs/compliance, scripts/compliance/check_pack.py). Owner statements "
        "of 2 Oct are kept in Comments. Anything the repository cannot prove is marked TO CONFIRM or NOT IN REPO.")

    wb.calculation.fullCalcOnLoad = True  # Dashboard formulas refresh when the copy is opened
    out = out_dir / (Path(path).stem.split("-", 1)[-1] + "_updated.xlsx")
    wb.save(out)
    return out, seen


def update_checklist(path, out_dir):
    cw = yaml.safe_load((PACK / "crosswalk.yaml").read_text(encoding="utf-8"))
    docs = {t["id"]: t["doc"] for t in cw["tasks"]}
    wb = openpyxl.load_workbook(path)
    ws = wb["WORK CHECKLIST"]
    h = header_index(ws)
    for r in ws.iter_rows(min_row=2):
        tid = r[h["Task ID"]].value
        if tid not in docs:
            continue
        old = r[h["Notes"]].value or ""
        r[h["Notes"]].value = (f"Draft v1 delivered 2026-10-19: {docs[tid]} (PDF in the documentation pack). "
                               f"Awaiting review/approval as listed.\n{old}")
    wb.calculation.fullCalcOnLoad = True
    out = out_dir / (Path(path).stem.split("-", 1)[-1] + "_updated.xlsx")
    wb.save(out)
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--checklist", required=True)
    p.add_argument("--workbook", action="append", default=[], help="key=path")
    p.add_argument("--out", required=True)
    a = p.parse_args()
    out_dir = Path(a.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    controls, risks = load_controls(), load_risks()
    cw = yaml.safe_load((PACK / "crosswalk.yaml").read_text(encoding="utf-8"))
    for spec in a.workbook:
        key, path = spec.split("=", 1)
        out, seen = update_risk_workbook(path, controls, risks, out_dir)
        expected = {c["id"] for c in cw["controls"] if c["workbook"] == key}
        missing = sorted(expected - seen)
        print(f"{out}: {len(seen)}/{len(expected)} controls updated" + (f"; missing {missing}" if missing else ""))
    print(update_checklist(a.checklist, out_dir))


if __name__ == "__main__":
    main()
