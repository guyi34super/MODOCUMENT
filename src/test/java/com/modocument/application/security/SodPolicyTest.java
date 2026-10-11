package com.modocument.application.security;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SoD policy (SEC-012 conflict table, SEC-024): the shared runtime gate
 * {@link SodPolicy#verifyDistinct}, the data of the conflict table, and the
 * STRUCTURAL assignment-time argument — a user holds exactly one role, so
 * pair-assignment (toxic role combination) conflicts cannot occur. If a
 * "secondary roles" feature ever lands, {@link #aUserHoldsExactlyOneRole()}
 * fails and the SoD analysis in docs/security/role-catalogue-and-sod.md
 * must be redone.
 */
class SodPolicyTest {

    private final SodPolicy policy = new SodPolicy();

    // ------------------------------------------------------- the runtime gate

    @Test
    void theSameUserOnBothSidesIsRefusedWithTheOperationsMessage() {
        assertThatThrownBy(() -> policy.verifyDistinct(7, 7, SodPolicy.Operation.CHANGE_APPROVAL))
                .isInstanceOf(SodPolicy.SodViolationException.class)
                .isInstanceOf(IllegalStateException.class) // → 409 via the handlers
                .hasMessageContaining("Self-approval is blocked");

        assertThatThrownBy(() -> policy.verifyDistinct(7, 7, SodPolicy.Operation.DOC_RELEASE))
                .isInstanceOf(SodPolicy.SodViolationException.class)
                .hasMessageContaining("Self-release is blocked");
    }

    @Test
    void distinctUsersPassEveryOperation() {
        for (SodPolicy.Operation operation : SodPolicy.Operation.values()) {
            assertThatCode(() -> policy.verifyDistinct(1, 2, operation))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void aViolationNamesItsOperationForTheAuditTrail() {
        assertThatThrownBy(() -> policy.verifyDistinct(3, 3, SodPolicy.Operation.DOC_RELEASE))
                .isInstanceOfSatisfying(SodPolicy.SodViolationException.class, violation ->
                        assertThat(violation.operation().auditAction())
                                .isEqualTo("DOC_SELF_RELEASE_BLOCKED"));
    }

    // -------------------------------------------------- the conflict table

    @Test
    void theConflictTableHoldsExactlyTheTwoDocumentedOperations() {
        assertThat(SodPolicy.Operation.values())
                .as("a new SoD conflict must be added to "
                        + "docs/security/role-catalogue-and-sod.md alongside")
                .containsExactly(SodPolicy.Operation.CHANGE_APPROVAL,
                        SodPolicy.Operation.DOC_RELEASE);

        assertThat(SodPolicy.Operation.CHANGE_APPROVAL.objectType()).isEqualTo("PendingChange");
        assertThat(SodPolicy.Operation.CHANGE_APPROVAL.auditAction())
                .isEqualTo("SELF_APPROVAL_BLOCKED");
        assertThat(SodPolicy.Operation.DOC_RELEASE.objectType()).isEqualTo("DocInstance");
        assertThat(SodPolicy.Operation.DOC_RELEASE.auditAction())
                .isEqualTo("DOC_SELF_RELEASE_BLOCKED");
    }

    // --------------------------------- the structural assignment-time layer

    /**
     * SEC-024 layer 1: role-pair conflicts are impossible because an
     * account's role is ONE enum-typed field — there is no role collection
     * to combine toxic pairs in, and setRole REPLACES the role.
     */
    @Test
    void aUserHoldsExactlyOneRole() throws Exception {
        List<Field> roleFields = Arrays.stream(AppUser.class.getDeclaredFields())
                .filter(field -> field.getType() == Role.class)
                .toList();
        assertThat(roleFields)
                .as("AppUser must carry exactly one single-valued Role field")
                .hasSize(1);

        // No field holds a COLLECTION of roles either.
        assertThat(Arrays.stream(AppUser.class.getDeclaredFields())
                .filter(field -> java.util.Collection.class.isAssignableFrom(field.getType()))
                .toList())
                .as("AppUser must not grow role (or any) collections without "
                        + "redoing the SoD analysis")
                .isEmpty();

        // Replacing the role really replaces it.
        AppUser user = new AppUser();
        user.setRole(Role.READ_WRITE);
        user.setRole(Role.APPROVER);
        assertThat(user.getRole()).isEqualTo(Role.APPROVER);
    }

    /**
     * The closed catalogue the conflict analysis was done for: the four bank
     * roles, plus the two phase-2 roles that are never a bank user's role.
     */
    @Test
    void theRoleCatalogueIsTheDocumentedSix() {
        assertThat(Role.values()).containsExactly(
                Role.ADMIN, Role.APPROVER, Role.READ_WRITE, Role.READ_ONLY,
                Role.PLATFORM_ADMIN, Role.API_CLIENT);
        assertThat(Role.values()).filteredOn(Role::isBankRole).containsExactly(
                Role.ADMIN, Role.APPROVER, Role.READ_WRITE, Role.READ_ONLY);
    }
}
