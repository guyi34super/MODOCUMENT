package com.modocument.application.security;

import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ws21: the one rule behind every role-assignment path — a customer bank
 * admits exactly the four bank roles, the platform bank exactly
 * PLATFORM_ADMIN, and API_CLIENT is assignable nowhere.
 */
class RoleAssignmentPolicyTest {

    @Test
    void customerBanksAdmitExactlyTheFourBankRoles() {
        assertThat(RoleAssignmentPolicy.allowedRoles(BankKind.CUSTOMER))
                .containsExactlyInAnyOrder(Role.ADMIN, Role.APPROVER, Role.READ_WRITE,
                        Role.READ_ONLY);
        for (Role role : Role.values()) {
            assertThat(RoleAssignmentPolicy.isAssignable(BankKind.CUSTOMER, role))
                    .as("%s in a customer bank", role)
                    .isEqualTo(role.isBankRole());
        }
    }

    @Test
    void thePlatformBankAdmitsOnlyPlatformAdmin() {
        assertThat(RoleAssignmentPolicy.allowedRoles(BankKind.PLATFORM))
                .containsExactly(Role.PLATFORM_ADMIN);
        for (Role role : EnumSet.complementOf(EnumSet.of(Role.PLATFORM_ADMIN))) {
            assertThat(RoleAssignmentPolicy.isAssignable(BankKind.PLATFORM, role))
                    .as("%s in the platform bank", role)
                    .isFalse();
        }
    }

    @Test
    void apiClientAndNullAreAssignableNowhere() {
        for (BankKind kind : BankKind.values()) {
            assertThat(RoleAssignmentPolicy.isAssignable(kind, Role.API_CLIENT)).isFalse();
            assertThat(RoleAssignmentPolicy.isAssignable(kind, null)).isFalse();
        }
    }

    @Test
    void anUnknownKindFallsBackToTheCustomerVocabulary() {
        // BankDirectory answers CUSTOMER for an unknown bank; a null kind must
        // never open the platform role either.
        assertThat(RoleAssignmentPolicy.isAssignable(null, Role.PLATFORM_ADMIN)).isFalse();
        assertThat(RoleAssignmentPolicy.isAssignable(null, Role.READ_ONLY)).isTrue();
    }

    @Test
    void refusalsKeepTheWs09WordingAndNameTheAllowedRoles() {
        assertThatThrownBy(() ->
                RoleAssignmentPolicy.requireAssignable(BankKind.CUSTOMER, Role.PLATFORM_ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown role 'PLATFORM_ADMIN'; use ADMIN, APPROVER, READ_WRITE or "
                        + "READ_ONLY.");
        assertThatThrownBy(() ->
                RoleAssignmentPolicy.requireAssignable(BankKind.PLATFORM, Role.ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown role 'ADMIN'; use PLATFORM_ADMIN.");
        assertThat(RoleAssignmentPolicy.requireAssignable(BankKind.PLATFORM, Role.PLATFORM_ADMIN))
                .isEqualTo(Role.PLATFORM_ADMIN);
    }

    @Test
    void thePlatformQuorumIsTwo() {
        assertThat(RoleAssignmentPolicy.MIN_ACTIVE_PLATFORM_ADMINS).isEqualTo(2);
    }
}
