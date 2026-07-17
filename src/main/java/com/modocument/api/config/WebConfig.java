package com.modocument.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Same origins as the original AllowReactApp CORS policy. */
    private static final String[] ALLOWED_ORIGINS = {
            "http://localhost:3000",
            "http://localhost:8004",
            "http://localhost:8080",
            "https://localhost:3000",
            "https://localhost:8004",
            "https://localhost:8080"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(ALLOWED_ORIGINS)
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
