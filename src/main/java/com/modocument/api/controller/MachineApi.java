package com.modocument.api.controller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller whose routes are called by machine clients (ws26):
 * {@code IntegrationApiInterceptor} meters every call in the per-client
 * bucket {@code client:<apiClientId>}, stamps the client's LastUsedAt and
 * audits the call with the client as actor ({@code INTEGRATION_API_CALL}).
 * SE5's agent controller may carry it too.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MachineApi {
}
