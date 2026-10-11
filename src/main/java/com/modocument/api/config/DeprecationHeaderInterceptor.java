package com.modocument.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Adds the deprecation headers (ws28, contracts §8; RFC 9745 / RFC 8594) to
 * every response of a {@link DeprecatedEndpoint} handler. Runs in
 * {@code preHandle}, before the handler writes a byte, so error answers carry
 * them too. The route itself is untouched.
 */
public class DeprecationHeaderInterceptor implements HandlerInterceptor {

    static final String LINK = "</docs/api/versioning-policy.md>; rel=\"deprecation\"";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        DeprecatedEndpoint deprecated = find(handler);
        if (deprecated != null) {
            response.setHeader("Deprecation", "true");
            response.setHeader("Sunset", httpDate(deprecated.sunset()));
            response.setHeader("Link", LINK);
        }
        return true;
    }

    /** The handler's annotation, method first, then its controller. */
    static DeprecatedEndpoint find(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        DeprecatedEndpoint onMethod = AnnotatedElementUtils.findMergedAnnotation(
                method.getMethod(), DeprecatedEndpoint.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(
                method.getBeanType(), DeprecatedEndpoint.class);
    }

    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    /** {@code 2027-04-01} → {@code Thu, 01 Apr 2027 00:00:00 GMT} (RFC 7231 IMF-fixdate). */
    static String httpDate(String isoDate) {
        return IMF_FIXDATE.format(LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC));
    }
}
