package com.modocument.infrastructure.ai;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connect and read timeouts for the AI client (ws23, QA finding OBS-03).
 *
 * <p>Spring AI's OpenAI client is built on the {@code RestClient.Builder}
 * Spring Boot auto-configures, whose HTTP client has no read timeout by
 * default: a model that accepts the request and never answers held the
 * ingestion job forever. This customizer keeps Boot's choice of HTTP client
 * and its other request-factory settings and adds
 * {@code modocument.ai.http.connect-timeout} / {@code read-timeout}. A timeout
 * surfaces as a {@code ResourceAccessException}, which Spring AI does not
 * retry, the circuit breaker counts and the parse stage classifies as
 * {@code TIMEOUT} before falling back to code.
 *
 * <p>Scope: the customizer applies to every {@code RestClient.Builder} taken
 * from the application context. Today only the AI client uses one; the other
 * outbound clients (docling, webhooks, SIEM forwarder) build their own
 * request factories with their own timeouts.
 */
@Configuration
public class AiHttpClientConfig {

    @Bean
    public RestClientCustomizer aiHttpTimeoutsRestClientCustomizer(
            AiClientProperties properties,
            ObjectProvider<ClientHttpRequestFactoryBuilder<?>> requestFactoryBuilder,
            ObjectProvider<ClientHttpRequestFactorySettings> requestFactorySettings) {
        ClientHttpRequestFactoryBuilder<?> builder =
                requestFactoryBuilder.getIfAvailable(ClientHttpRequestFactoryBuilder::detect);
        ClientHttpRequestFactorySettings settings = requestFactorySettings
                .getIfAvailable(ClientHttpRequestFactorySettings::defaults)
                .withConnectTimeout(properties.getHttp().getConnectTimeout())
                .withReadTimeout(properties.getHttp().getReadTimeout());
        return restClient -> restClient.requestFactory(builder.build(settings));
    }
}
