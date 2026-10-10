package com.modocument.api.config;

import com.modocument.infrastructure.ratelimit.ApiRateLimitInterceptor;
import com.modocument.infrastructure.ratelimit.LlmQuotaInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.pattern.PathPatternParser;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final LlmQuotaInterceptor llmQuota;
    private final ApiRateLimitInterceptor apiRateLimit;
    private final MachineApiInterceptor machineApi;

    public WebConfig(@Value("${modocument.cors.allowed-origins}") String[] allowedOrigins,
                     LlmQuotaInterceptor llmQuota,
                     ApiRateLimitInterceptor apiRateLimit,
                     MachineApiInterceptor machineApi) {
        this.allowedOrigins = allowedOrigins;
        this.llmQuota = llmQuota;
        this.apiRateLimit = apiRateLimit;
        this.machineApi = machineApi;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Meter every LLM-spending endpoint per user (F-007). Matched with the
        // SAME case-insensitive parser as the handler mapping: Spring's
        // default interceptor matching is case-sensitive, so /API/AIFILES/SEND
        // used to reach the handler while skipping the quota (ws17 fix).
        registry.addInterceptor(new MappedInterceptor(
                LlmQuotaInterceptor.PATHS.toArray(String[]::new), null, llmQuota,
                caseInsensitiveParser()));
        // Blueprint upload's AI section breakdown meters the same per-user
        // quota inside AiSectionSplitter (an interceptor on the path would
        // also meter GET list requests, which spend nothing).

        // Per-user fixed-window limits on every other expensive endpoint
        // (ws17, SEC-007/E2): one interceptor on every request, which picks
        // its bucket from the handler's matched route pattern + method (so
        // casing cannot dodge it, and GET /api/ingestion/jobs stays
        // unmetered while the POST on the same path is).
        registry.addInterceptor(apiRateLimit);

        // ws26: per-client limit, LastUsedAt and the per-call audit of the
        // machine API (selected by the controller's @MachineApi).
        registry.addInterceptor(machineApi);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // ASP.NET Core routing is case-insensitive; keep that behaviour so
        // existing clients hitting e.g. /api/aifiles/send keep working.
        configurer.setPatternParser(caseInsensitiveParser());
    }

    private static PathPatternParser caseInsensitiveParser() {
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        return parser;
    }
}
