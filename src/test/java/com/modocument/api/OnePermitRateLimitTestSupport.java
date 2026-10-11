package com.modocument.api;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * One Spring context for the rate-limit suites: every per-user bucket
 * ({@code modocument.ratelimit.*}, ApiRateLimitInterceptor) and the per-user
 * LLM quota (LlmQuotaInterceptor) sized to ONE permit, so the first call
 * spends it and the second is throttled.
 *
 * <p>The buckets are per user and live for the context's lifetime, so every
 * test in these suites signs in as FRESH users — which is also what lets
 * several suites share this context (it used to be one context per suite).
 */
@SpringBootTest(properties = {
        "modocument.ratelimit.uploads=1",
        "modocument.ratelimit.ingestion=1",
        "modocument.ratelimit.transform=1",
        "modocument.ratelimit.exports=1",
        "modocument.ratelimit.doc-generate=1",
        "modocument.ratelimit.extraction=1",
        "modocument.ratelimit.dsr=1",
        "modocument.ratelimit.password-change=1",
        "modocument.ratelimit.mfa-code=1",
        "modocument.ratelimit.admin-jobs=1",
        "modocument.ratelimit.admin-security=1",
        "modocument.security.rate-limit.llm.per-user-max=1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class OnePermitRateLimitTestSupport {
}
