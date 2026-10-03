package com.modocument.api.security;

import com.modocument.domain.model.Permission;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the one {@link Permission} a handler method (or a whole controller,
 * when every handler needs the same one) requires. A composed
 * {@code @PreAuthorize} meta-annotation: the {@code {value}} placeholder is
 * substituted with the enum constant's name by Spring Security's annotation
 * templating (see {@code MethodSecurityConfig}), so
 * {@code @RequiresPermission(Permission.TEMPLATE_READ)} evaluates
 * {@code @perm.has('TEMPLATE_READ')} against the caller's JWT role.
 *
 * <p>{@code RoutePermissionCoverageTest} asserts that every mapped handler
 * carries exactly one of these (or sits on the explicit public-route
 * allowlist), so forgetting the annotation on a new endpoint fails the build
 * rather than shipping an unguarded route.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("@perm.has('{value}')")
public @interface RequiresPermission {

    /** The permission required to invoke the annotated handler(s). */
    Permission value();
}
