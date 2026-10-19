# SUP-S13 — External AI / LLM provider controls (technical, not just contractual)

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 03 Software Engineering | Software Engineering | P1 | Partially Implemented | SUP-S13-E01 | SUP-S13-T01 | R-SUP-S13 |

## Requirement
If any feature calls an AI provider, govern the data path technically: one gateway, pseudonymisation and output scanning, a tenant check in the prompt builder, a per-tenant switch, provider settings recorded, and written acceptance of the residual risks. Acceptance (gates C8–C11): a prompt containing seeded PII leaves the gateway with the PII replaced by tokens.

## How MoDocument meets it today

Gateway design note: `docs/security/ai-controls.md` (updated 2026-10-19). Summary:

| Control | Mechanism | Proof |
|---|---|---|
| One internal gateway | `AiGateway` is the only class allowed to use the Spring AI chat types; a build guard and a Semgrep rule fail on any other use; clients never call a model | `src/main/java/com/modocument/infrastructure/ai/AiGateway.java`, `NoDirectChatModelAccessGuardTest`, `.semgrep/modocument.yml` |
| Pseudonymisation / tokenizer | `PiiTokenizer` replaces sensitive values with stable `[ID_n]`/`[EMAIL_n]` tokens before the prompt; the map lives in memory for one call; answers are detokenized locally | `src/main/java/com/modocument/application/privacy/PiiTokenizer.java`, `PiiTokenizerTest` |
| Leak scan on the assembled prompt | NIC, account and e-mail shapes in the final prompt block the call (`AI_PROMPT_BLOCKED_PII`) | `AiGatewayC9Test` |
| Tenant guard | The bound tenant must equal the envelope's bank and every part's source bank; a missing tenant is refused (`AI_PROMPT_REFUSED`) | `AiGatewayC9Test` |
| Output scan | Every completion scanned for identifier leaks and instruction/injection echoes; STRICT purposes reject, LENIENT scrub and flag | `src/main/java/com/modocument/infrastructure/ai/AiOutputScanner.java`, `AiOutputScannerTest`, `AiOutputPolicyC10Test` |
| Local-first | Default endpoint `http://localhost:11434` (Ollama, `qwen2.5`); external endpoint only by explicit configuration; vision OCR off by default | `src/main/resources/application.yml`, `AiGatewayC8LoggingTest` |
| Per-tenant AI switch | `ai.enabled` per bank (Data protection tab), SECURITY tier → maker/checker to change; off → no model call, 503 or code fallback | `src/main/java/com/modocument/infrastructure/ai/AiPreflight.java`, `AiPreflightTest`, `AiCircuitBreakerTest`, `SettingsApiTest`, `docs/settings/catalogue.md` |
| Quota | 60 LLM calls per user per hour on the interactive AI routes (429 with `Retry-After`) | `src/main/java/com/modocument/infrastructure/ratelimit/LlmQuotaInterceptor.java`, `LlmQuotaRetryAfterIntegrationTest` |
| Circuit breaker, timeouts, fallback | Opens after 5 counted failures for 2 min; 5 s / 120 s timeouts; code extraction stands in | `src/main/java/com/modocument/infrastructure/ai/AiCircuitBreaker.java`, `IngestionParseStepImplTest` |
| Result cache | Bank-scoped under RLS, switchable per bank, purged after 30 days and by a DSR erase | `src/main/java/com/modocument/infrastructure/ai/AiResultCache.java`, `AiResultCacheRlsPgIT` |
| No storage or logging of prompts | Content never logged; audit carries purpose, model and sizes only | `AiGatewayC8LoggingTest` |
| Text output only | The model returns text/JSON that code validates; it triggers no action | `docs/security/ai-controls.md` |

**Provider settings.** No provider console exists; the endpoint, key and model are deployment variables (`MODOCUMENT_AI_BASE_URL`, `MODOCUMENT_AI_API_KEY`, `MODOCUMENT_AI_MODEL`) in `src/main/resources/application.yml`. The Terraform task definition sets none (`infra/terraform/modules/ecs-app/main.tf`), so the production model location is undecided in the repository. No external provider is configured today (workbook: local models only, no external AI API).

**Residual risks** for the C11 sign-off are listed in `docs/security/ai-controls.md` ("Residual risks"): no NER-grade extraction, no input cleaning (prompt injection), figures written by the model, no model pinning. They match the workbook's four "not adopted" ideas. The written acceptance by Reezvi Pydiah does not exist yet.

## Evidence SUP-S13-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| Gateway design note | `docs/security/ai-controls.md` | repo |
| Provider settings | `src/main/resources/application.yml` (AI section); "Provider settings" in `docs/security/ai-controls.md` | repo |
| Per-tenant AI switch | `docs/settings/catalogue.md` (`ai.enabled`), `src/main/java/com/modocument/domain/model/settings/SettingKeys.java` | repo |
| Cross-tenant leakage tests (runs R2, R3) | `AiGatewayC9Test`, `AiResultCacheRlsPgIT`; `docs/compliance/evidence/A7-runs.md` | run |
| Residual-risk acceptance (C11) | — | TO CONFIRM (organisational) |
| Provider retention / training / region settings and DPA for any external provider | — | TO CONFIRM (organisational) |

## Test SUP-S13-T01
- **Procedure:** Re-run the AI control tests (run R2): `AiGatewayC9Test` (seeded real-format NIC and account numbers through transform and auto-map; two-bank prompt; tenant mismatch; missing tenant; raw identifiers in an assembled prompt), `AiGatewayC8LoggingTest`, `AiOutputScannerTest`, `AiOutputPolicyC10Test`, `NoDirectChatModelAccessGuardTest`, `AiGatewayTest`, `AiPreflightTest`, `AiCircuitBreakerTest`, `LlmQuotaRetryAfterIntegrationTest`, `PiiTokenizerTest`, `IngestionParseStepImplTest`, `SettingsApiTest`; and the cross-bank cache test `AiResultCacheRlsPgIT` on PostgreSQL (run R3).
- **Expected result:** seeded PII leaves the gateway as tokens only; any cross-bank prompt is refused and audited; another bank never hits a bank's cached result; output scanning, switch, quota and breaker behave as specified.
- **Actual result:** R2: 100 tests, 0 failures (`IngestionParseStepImplTest` 32, `PiiTokenizerTest` 15, `AiCircuitBreakerTest` 10, `AiOutputScannerTest` 8, `AiPreflightTest` 8, `SettingsApiTest` 8, `AiGatewayC9Test` 6, `AiGatewayTest` 5, `AiOutputPolicyC10Test` 3, `AiGatewayC8LoggingTest` 3, `NoDirectChatModelAccessGuardTest` 1, `LlmQuotaRetryAfterIntegrationTest` 1). `AiGatewayC9Test#transformUploadWithRealIdentifiersSendsOnlyTokens` passed: the model request contained tokens only. `promptMixingTwoBanksIsRefusedAndAudited`, `envelopeBankDifferentFromBoundTenantIsRefused` and `missingTenantContextIsRefusedAndAudited` passed. R3: `AiResultCacheRlsPgIT#anotherBankNeverHitsABanksCachedResult` passed.
- **Result:** Pass (technical controls). The control stays Partially Implemented until the C11 acceptance is signed.
- **Tester:** Agent A7 (automated re-performance); human sign-off required. **Date:** 2026-10-19

## Gap and remediation
| Gap | Type | Action | Owner | Target date | Phase |
|---|---|---|---|---|---|
| Residual risks not accepted in writing (C11) | Org | Sign the acceptance of the four residual risks in `docs/security/ai-controls.md` | Reezvi Pydiah | 2026-11-02 (proposed) | Day 30 |
| Production model location undecided | Org | Decide local model in AWS vs external provider; if external: record retention, training-off, region and DPA (PRIV-022), assess the cross-border transfer with the DPO (PRIV-014), add the endpoint to the egress allow-list | Bhavish Nobeen; the DPO | 2026-11-30 (proposed) | Day 60 |
| HTTPS not enforced for an external AI endpoint | Build | Refuse a non-HTTPS, non-loopback `MODOCUMENT_AI_BASE_URL` when guardrails are enforced | Software Engineering — Bhavish Nobeen | 2026-12-18 (proposed) | Day 60 |

## Risk R-SUP-S13
- **Risk:** bank customer data leaves the jurisdiction into a provider's retention or training pipeline, or leaks across tenants through model output; prompt injection or a wrong model-written figure is accepted.
- **Cause:** AI features process customer documents; pseudonymisation is shape-based; no input cleaning; no model pinning.
- **Mitigation:** single gateway with tokenization, tenant guard, output scan, local-first default, per-bank switch, quota and breaker; human review before release; written acceptance of residual risks.
- Likelihood, impact and residual: set in MO-031.

## To confirm
- Written acceptance of the AI residual risks (C11) by Reezvi Pydiah: TO CONFIRM (organisational).
- Production AI endpoint and, if external, the provider's retention, training, region and DPA settings: TO CONFIRM (organisational).
- Provider settings screenshot from a running environment: NOT IN REPO (live cloud).
