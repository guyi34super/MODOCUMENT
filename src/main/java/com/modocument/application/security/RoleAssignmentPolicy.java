package com.modocument.application.security;

import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.Role;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which {@link Role}s a user of a given kind of bank may hold (ws21) — the
 * single rule every role-assignment path asks: user administration
 * ({@code UserAdminUseCase}), the USER_CREATE / USER_ROLE_CHANGE
 * maker/checker payloads ({@code ChangePayloads}) and the platform
 * bootstrap. The V210 database trigger enforces the same placement as a
 * backstop.
 * <ul>
 *   <li>{@link BankKind#CUSTOMER} — the four bank roles
 *       ({@link Role#isBankRole()}).</li>
 *   <li>{@link BankKind#PLATFORM} — {@link Role#PLATFORM_ADMIN} only: the
 *       platform bank holds platform staff and nothing else.</li>
 * </ul>
 * {@link Role#API_CLIENT} is never assignable: machine clients are not
 * {@code AppUsers} rows. OIDC maps bank roles only and never provisions into
 * the platform bank (OidcLoginService).
 */
public final class RoleAssignmentPolicy {

    /**
     * The platform bank never drops below this many enabled PLATFORM_ADMIN
     * accounts: bootstrap creates exactly two, and disabling or demoting one
     * that would leave fewer is refused (409) — every platform change is
     * maker/checker, so one admin alone could approve nothing.
     */
    public static final int MIN_ACTIVE_PLATFORM_ADMINS = 2;

    private static final Set<Role> CUSTOMER_ROLES = Collections.unmodifiableSet(
            EnumSet.of(Role.ADMIN, Role.APPROVER, Role.READ_WRITE, Role.READ_ONLY));

    private static final Set<Role> PLATFORM_ROLES =
            Collections.unmodifiableSet(EnumSet.of(Role.PLATFORM_ADMIN));

    private RoleAssignmentPolicy() {
    }

    /** The roles a user of a bank of this kind may hold (never null). */
    public static Set<Role> allowedRoles(BankKind kind) {
        return kind == BankKind.PLATFORM ? PLATFORM_ROLES : CUSTOMER_ROLES;
    }

    public static boolean isAssignable(BankKind kind, Role role) {
        return role != null && allowedRoles(kind).contains(role);
    }

    /**
     * Refuses a role the bank kind does not admit, with the same 400 shape
     * the user-admin endpoints always used.
     *
     * @throws IllegalArgumentException when {@code role} is not assignable
     */
    public static Role requireAssignable(BankKind kind, Role role) {
        if (!isAssignable(kind, role)) {
            throw new IllegalArgumentException(refusal(kind, role == null ? null : role.name()));
        }
        return role;
    }

    /** The refusal message for a role value that is unknown or not assignable here. */
    public static String refusal(BankKind kind, String value) {
        return "Unknown role '" + value + "'; use " + choices(kind) + ".";
    }

    private static String choices(BankKind kind) {
        // Declaration order is the privilege order: "ADMIN, APPROVER,
        // READ_WRITE or READ_ONLY" — the wording the ws09 endpoints used.
        var names = allowedRoles(kind).stream().map(Role::name).collect(Collectors.toList());
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1))
                + " or " + names.get(names.size() - 1);
    }
}
