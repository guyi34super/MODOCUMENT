package com.modocument.api.config;

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

    public WebConfig(@Value("${modocument.cors.allowed-origins}") String[] allowedOrigins,
                     LlmQuotaInterceptor llmQuota) {
        this.allowedOrigins = allowedOrigins;
        this.llmQuota = llmQuota;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Meter every LLM-spending endpoint per user (F-007). chat-edit carries
        // a job id in the path, hence the wildcard segment.
        registry.addInterceptor(llmQuota).addPathPatterns(
                "/api/aifiles/send",
                "/api/automapper/generate",
                "/api/ingestion/jobs/*/chat-edit");
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
