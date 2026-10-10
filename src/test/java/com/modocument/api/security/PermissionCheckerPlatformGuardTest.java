package com.modocument.api.security;

import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.security.RolePermissions;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.PasswordChangeRequiredGate;
import com.modocument.testsupport.FixedUser;
import com.modocument.testsupport.StubBankDirectory;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The ws21 platform guard of the {@code @perm} bean, decision by decision:
 * platform permissions — and anything held through the PLATFORM_ADMIN
 * role — need the caller's bank to BE the platform bank, on top of the
 * role matrix. HTTP-level proof: PlatformAccessIntegrationTest.
 */
class PermissionCheckerPlatformGuardTest {

    private static final int CUSTOMER_BANK = 1;
    private static final int PLATFORM_BANK = 50;

    private final PasswordChangeRequiredGate gate = mock(PasswordChangeRequiredGate.class);
    private final StubBankDirectory directory =
            new StubBankDirectory().customer(CUSTOMER_BANK).platform(PLATFORM_BANK);

    private PermissionChecker checker(int bankId, Role role) {
        return checker(new FixedUser(7, bankId, role), directory);
    }

    private PermissionChecker checker(CurrentUserProvider user, PlatformBankLookup lookup) {
        return new PermissionChecker(user, gate, lookup);
    }

    @Test
    void aPlatformAdminOfThePlatformBankHoldsEveryPlatformPermission() {
        PermissionChecker perm = checker(PLATFORM_BANK, Role.PLATFORM_ADMIN);
        for (Permission permission : RolePermissions.PLATFORM_PERMISSIONS) {
            assertThat(perm.has(permission.name())).as("%s", permission).isTrue();
        }
        assertThat(perm.has("USER_ADMIN")).isTrue();
        assertThat(perm.has("PROFILE_SELF")).isTrue();
    }

    @Test
    void aPlatformAdminClaimFromAnyOtherBankGetsNothing() {
        PermissionChecker perm = checker(CUSTOMER_BANK, Role.PLATFORM_ADMIN);
        for (Permission permission : RolePermissions.granted(Role.PLATFORM_ADMIN)) {
            assertThat(perm.has(permission.name()))
                    .as("PLATFORM_ADMIN outside the platform bank must not hold %s", permission)
                    .isFalse();
        }
    }

    @Test
    void aPlatformAdminHasNoCustomerDataPermissions() {
        PermissionChecker perm = checker(PLATFORM_BANK, Role.PLATFORM_ADMIN);
        for (String permission : new String[] {"TEMPLATE_READ", "INGESTION_READ", "DOC_READ",
                "EXTRACTION_READ", "EXPORT_RUN", "SETTINGS_WRITE", "INTEGRATION_ADMIN"}) {
            assertThat(perm.has(permission)).as("%s", permission).isFalse();
        }
    }

    @Test
    void bankRolesNeverHoldPlatformPermissionsEvenInsideThePlatformBank() {
        for (Role role : new Role[] {Role.ADMIN, Role.APPROVER, Role.READ_WRITE,
                Role.READ_ONLY}) {
            for (int bank : new int[] {CUSTOMER_BANK, PLATFORM_BANK}) {
                PermissionChecker perm = checker(bank, role);
                for (Permission permission : RolePermissions.PLATFORM_PERMISSIONS) {
                    assertThat(perm.has(permission.name()))
                            .as("%s in bank %d must not hold %s", role, bank, permission)
                            .isFalse();
                }
            }
        }
        // ... while a customer ADMIN keeps its bank powers.
        assertThat(checker(CUSTOMER_BANK, Role.ADMIN).has("USER_ADMIN")).isTrue();
    }

    @Test
    void noPlatformBankYetMeansNoPlatformPermissionForAnyone() {
        PlatformBankLookup none = OptionalInt::empty;
        PermissionChecker perm = checker(new FixedUser(7, PLATFORM_BANK, Role.PLATFORM_ADMIN), none);
        assertThat(perm.has("PLATFORM_READ")).isFalse();
        assertThat(perm.has("PROFILE_SELF")).isFalse();
    }

    @Test
    void aFailingLookupDeniesInsteadOfCrashing() {
        PlatformBankLookup broken = () -> {
            throw new IllegalStateException("Could not resolve the platform bank");
        };
        PermissionChecker perm = checker(new FixedUser(7, PLATFORM_BANK, Role.PLATFORM_ADMIN),
                broken);
        assertThat(perm.has("PLATFORM_READ")).isFalse();
    }

    @Test
    void apiClientTokensGetNothingUntilScopesLand() {
        PermissionChecker perm = checker(CUSTOMER_BANK, Role.API_CLIENT);
        for (Permission permission : RolePermissions.CLIENT_SCOPES) {
            assertThat(perm.has(permission.name())).as("%s", permission).isFalse();
        }
    }
}
