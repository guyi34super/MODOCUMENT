package com.modocument.api.config;

import com.modocument.infrastructure.ratelimit.ApiRateLimitInterceptor;
import com.modocument.infrastructure.ratelimit.LlmQuotaInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final LlmQuotaInterceptor llmQuota;
    private final ApiRateLimitInterceptor apiRateLimit;

    public WebConfig(@Value("${modocument.cors.allowed-origins}") String[] allowedOrigins,
                     LlmQuotaInterceptor llmQuota,
                     ApiRateLimitInterceptor apiRateLimit) {
        this.allowedOrigins = allowedOrigins;
        this.llmQuota = llmQuota;
        this.apiRateLimit = apiRateLimit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Meter every LLM-spending endpoint per user (F-007). chat-edit carries
        // a job id in the path, hence the wildcard segment.
        registry.addInterceptor(llmQuota).addPathPatterns(
                "/api/aifiles/send",
                "/api/automapper/generate",
                "/api/ingestion/jobs/*/chat-edit");
        // Blueprint upload's AI section breakdown meters the same per-user
        // quota inside AiSectionSplitter (an interceptor on the path would
        // also meter GET list requests, which spend nothing).

        // Per-user fixed-window limits on every expensive endpoint (ws17,
        // SEC-007/E2): one interceptor, bucket chosen by matched route +
        // method (so e.g. GET /api/ingestion/jobs stays unmetered while the
        // POST on the same path is).
        registry.addInterceptor(apiRateLimit).addPathPatterns(apiRateLimit.pathPatterns());
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
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        configurer.setPatternParser(parser);
    }
}
