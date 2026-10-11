package com.modocument.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler (or every handler of a controller) as DEPRECATED under
 * docs/api/versioning-policy.md §4 (ws28, contracts §8). One annotation
 * does all of it:
 * <ul>
 *   <li>{@link DeprecationHeaderInterceptor} adds {@code Deprecation: true},
 *       {@code Sunset: <RFC 7231 date>} and
 *       {@code Link: </docs/api/versioning-policy.md>; rel="deprecation"} to
 *       every response — the route keeps answering normally until the
 *       sunset;</li>
 *   <li>{@code OpenApiConfig} renders the operation {@code deprecated: true}
 *       with the replacement and sunset in its description.</li>
 * </ul>
 * Every annotated route is listed in the deprecation table of the policy
 * ({@code DeprecatedEndpointsTest} keeps them in step).
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DeprecatedEndpoint {

    /** The removal date, ISO-8601 ({@code yyyy-MM-dd}, midnight GMT). */
    String sunset() default DEFAULT_SUNSET;

    /** What to use instead (shown in the OpenAPI description). */
    String replacement() default "";

    /** The release that deprecated it. */
    String since() default "1.2.0";

    String DEFAULT_SUNSET = "2027-04-01";
}
