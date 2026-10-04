# DPIA template — PRIV-004

Data Protection Impact Assessment template for MoDocument processing
activities. Complete one per new processing purpose, new data category, new
recipient (e.g. a new LLM provider/model), or new storage location. File the
completed assessment beside this template as
`dpia-<yyyy-mm>-<short-name>.md`; the sign-off is the risk owner's (Reezvi
Pydiah) written approval.

Pre-filled pointers reference the standing evidence so each DPIA stays
short: [data-inventory.md](data-inventory.md),
[masking-matrix.md](masking-matrix.md),
[retention-and-dsr.md](retention-and-dsr.md),
[threat-control-matrix.md](threat-control-matrix.md).

---

## 1. Processing description

| Field | Answer |
|---|---|
| Name of processing | |
| Date / assessor | |
| Purpose (what, why, for whom) | |
| Lawful basis / contractual basis | |
| Data categories involved | map to rows of the [data inventory](data-inventory.md) |
| Data subjects | bank staff? bank customers whose documents are processed? |
| New category NOT in the inventory? | if yes: add it there first, with retention + protection |

## 2. Flows and recipients

| Field | Answer |
|---|---|
| Where does the data enter, move, rest? | extend the DFD in [data-inventory.md](data-inventory.md) if the flow is new |
| Third-party recipients | LLM endpoint? docling? external DB export target? |
| LLM exposure | Which calls see the data? Is PII tokenization in the path (fail-closed) or is this a raw-content path (extraction/OCR — name the justification)? |
| Cross-border transfer | af-south-1 primary; backups copy to ap-southeast-2 — does this processing add another region? |

## 3. Necessity & proportionality

- [ ] Could the purpose be met with less data (fewer fields, masked values,
      shorter retention)?
- [ ] Retention set and enforced by the purge job (or a documented reason
      why not) — [retention-and-dsr.md](retention-and-dsr.md)
- [ ] Access limited by existing permissions (name them) — no new blanket
      grants
- [ ] DSR coverage: can search/export/erase reach this data? (gate C12)

## 4. Risks

| Risk | Likelihood (L/M/H) | Impact (L/M/H) | Existing controls ([threat matrix](threat-control-matrix.md)) | Added controls needed |
|---|---|---|---|---|
| Unauthorized access (cross-tenant / insider) | | | | |
| Leak via LLM prompt or logs | | | | |
| Retention over-run / failure to erase | | | | |
| Integrity (wrong data in generated documents) | | | | |
| Availability (loss of the only copy) | | | | |

## 5. Decision

| Field | Answer |
|---|---|
| Residual risk | Low / Medium / High + rationale |
| Measures to implement before go-live of this processing | |
| Risk owner sign-off (name, date) | Reezvi Pydiah, |
| Review date | +12 months or on change |

---

*Standing DPIAs to complete first (known processing):* (1) document
ingestion & LLM extraction, (2) web/API/DB data extraction, (3) document
creation & release, (4) retention & DSR handling itself.
