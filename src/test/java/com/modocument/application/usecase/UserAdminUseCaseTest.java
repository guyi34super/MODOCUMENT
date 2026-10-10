package com.modocument.application.usecase;

import com.modocument.application.dto.AdminUserDto;
import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.RefreshTokenRepository;
import com.modocument.application.port.UserMfaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.testsupport.FixedUser;
import com.modocument.testsupport.StubBankDirectory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bank scoping, the self/last-admin footgun guards and the mover/leaver
 * token-death mechanics of ws09, against mocks. The caller is always the
 * ADMIN with id 100 in the default bank (1); cross-bank requests must look
 * exactly like missing ids. The last-admin guard is unreachable through the
 * API while the caller is themself an active admin of the bank (two active
 * admins exist the moment another one is targeted), so it is pinned here as
 * the defense-in-depth it is.
 */
class UserAdminUseCaseTest {

    private static final int CALLER = 100;
    private static final int PLATFORM_BANK = 50;
    private static final int PLATFORM_CALLER = 500;

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UserMfaRepository userMfa = mock(UserMfaRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final BankJpaRepository banks = mock(BankJpaRepository.class);
    private final AuthUseCase auth = mock(AuthUseCase.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final StubBankDirectory bankDirectory =
            new StubBankDirectory().customer(Bank.DEFAULT_BANK_ID).platform(PLATFORM_BANK);
    private final UserAdminUseCase useCase = new UserAdminUseCase(
            FixedUser.admin(CALLER), users, userMfa, refreshTokens, banks, auth, audit,
            bankDirectory);
    /** ws21: the same use case serving a platform admin of the platform bank. */
    private final UserAdminUseCase platformUseCase = new UserAdminUseCase(
            new FixedUser(PLATFORM_CALLER, PLATFORM_BANK, Role.PLATFORM_ADMIN), users, userMfa,
            refreshTokens, banks, auth, audit, bankDirectory);

    private static AppUser user(int id, int bankId, Role role) {
        AppUser user = new AppUser();
        user.setAppUserId(id);
        user.setBankId(bankId);
        user.setRole(role);
        user.setEmail("user" + id + "@example.com");
        user.setDisplayName("User " + id);
        user.setPasswordHash("hash");
        return user;
    }

    // ------------------------------------------------------------- listing

    @Test
    void listUsersQueriesOnlyTheCallersBankAndJoinsMfaState() {
        AppUser plain = user(1, Bank.DEFAULT_BANK_ID, Role.READ_ONLY);
        AppUser withMfa = user(2, Bank.DEFAULT_BANK_ID, Role.READ_WRITE);
        when(users.listByBank(Bank.DEFAULT_BANK_ID)).thenReturn(List.of(plain, withMfa));
        when(userMfa.enabledUserIds(anyCollection())).thenReturn(Set.of(2));

        List<AdminUserDto> listed = useCase.listUsers();

        assertThat(listed).extracting(AdminUserDto::id).containsExactly(1, 2);
        assertThat(listed).extracting(AdminUserDto::mfaEnabled).containsExactly(false, true);
        verify(users).listByBank(Bank.DEFAULT_BANK_ID);
    }

    // ------------------------------------------------------------- joiners

    @Test
    void createApprovedUserLandsInTheChangesBankWithMustChangePassword() {
        // ws10: the applier hands over the HASH computed at submit time —
        // the plaintext temporary password never reaches this layer.
        UserDto created = new UserDto(9, "new@example.com", "New", "{}", "READ_ONLY",
                Bank.DEFAULT_BANK_ID, true);
        when(auth.createUserWithHash("new@example.com", "{argon2}$fake-hash", "New",
                Bank.DEFAULT_BANK_ID, Role.READ_ONLY, true)).thenReturn(created);

        UserDto result = useCase.createApprovedUser("new@example.com", "New", Role.READ_ONLY,
                "{argon2}$fake-hash", Bank.DEFAULT_BANK_ID);

        assertThat(result).isSameAs(created);
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("USER_CREATED");
        assertThat(recorded.entityId()).isEqualTo("9");
        // No password material in the audit trail, ever.
        assertThat(String.valueOf(recorded.newValues())).doesNotContain("fake-hash");
    }

    // -------------------------------------------------------------- movers

    @Test
    void changeRoleRefusesUsersOfOtherBanksLikeMissingIds() {
        when(users.getById(7)).thenReturn(Optional.of(user(7, 2, Role.READ_ONLY)));

        assertThatThrownBy(() -> useCase.changeRole(7, Role.ADMIN))
                .isInstanceOf(NoSuchElementException.class);
        verify(users, never()).save(any(AppUser.class));
    }

    @Test
    void changeRoleRefusesTheCallersOwnRole() {
        when(users.getById(CALLER))
                .thenReturn(Optional.of(user(CALLER, Bank.DEFAULT_BANK_ID, Role.ADMIN)));

        assertThatThrownBy(() -> useCase.changeRole(CALLER, Role.READ_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("own role");
    }

    @Test
    void changeRoleGuardsTheLastActiveAdmin() {
        when(users.getById(7))
                .thenReturn(Optional.of(user(7, Bank.DEFAULT_BANK_ID, Role.ADMIN)));
        when(users.countActiveAdmins(Bank.DEFAULT_BANK_ID)).thenReturn(1L);

        assertThatThrownBy(() -> useCase.changeRole(7, Role.READ_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only remaining active administrator");
        verify(users, never()).save(any(AppUser.class));
    }

    @Test
    void changeRoleBumpsTokenVersionRevokesRefreshFamiliesAndAudits() {
        AppUser target = user(7, Bank.DEFAULT_BANK_ID, Role.READ_ONLY);
        when(users.getById(7)).thenReturn(Optional.of(target));
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUserDto changed = useCase.changeRole(7, Role.APPROVER);

        assertThat(changed.role()).isEqualTo("APPROVER");
        // THE B5 mechanism: the bump makes the RevocationValidator reject
        // every outstanding access token; the revocation stops re-minting.
        assertThat(target.getTokenVersion()).isEqualTo(1);
        verify(refreshTokens).revokeAllForUser(eq(7), any(Instant.class));
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("USER_ROLE_CHANGED");
        assertThat(recorded.oldValues()).isEqualTo(Map.of("role", "READ_ONLY"));
        assertThat(recorded.newValues()).isEqualTo(Map.of("role", "APPROVER"));
    }

    @Test
    void changeRoleToTheSameRoleIsANoOp() {
        AppUser target = user(7, Bank.DEFAULT_BANK_ID, Role.APPROVER);
        when(users.getById(7)).thenReturn(Optional.of(target));

        useCase.changeRole(7, Role.APPROVER);

        assertThat(target.getTokenVersion()).isZero();
        verify(users, never()).save(any(AppUser.class));
        verify(audit, never()).record(any(AuditEventSpec.class));
    }

    // ------------------------------------------------------------- leavers

    @Test
    void disableRefusesTheCallersOwnAccount() {
        when(users.getById(CALLER))
                .thenReturn(Optional.of(user(CALLER, Bank.DEFAULT_BANK_ID, Role.ADMIN)));

        assertThatThrownBy(() -> useCase.setDisabled(CALLER, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("own account");
    }

    @Test
    void disableGuardsTheLastActiveAdmin() {
        when(users.getById(7))
                .thenReturn(Optional.of(user(7, Bank.DEFAULT_BANK_ID, Role.ADMIN)));
        when(users.countActiveAdmins(Bank.DEFAULT_BANK_ID)).thenReturn(1L);

        assertThatThrownBy(() -> useCase.setDisabled(7, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only remaining active administrator");
        verify(users, never()).save(any(AppUser.class));
    }

    @Test
    void disableSetsTheFlagKillsEveryTokenAndAudits() {
        AppUser target = user(7, Bank.DEFAULT_BANK_ID, Role.READ_WRITE);
        when(users.getById(7)).thenReturn(Optional.of(target));
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUserDto disabled = useCase.setDisabled(7, true);

        assertThat(disabled.disabled()).isTrue();
        assertThat(target.isDisabled()).isTrue();
        assertThat(target.getTokenVersion()).isEqualTo(1);
        verify(refreshTokens).revokeAllForUser(eq(7), any(Instant.class));
        assertThat(recordedAudit().action()).isEqualTo("USER_DISABLED");
    }

    @Test
    void enableOnlyClearsTheFlag() {
        AppUser target = user(7, Bank.DEFAULT_BANK_ID, Role.READ_WRITE);
        target.setDisabled(true);
        when(users.getById(7)).thenReturn(Optional.of(target));
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUserDto enabled = useCase.setDisabled(7, false);

        assertThat(enabled.disabled()).isFalse();
        assertThat(target.getTokenVersion()).isZero();
        verify(refreshTokens, never()).revokeAllForUser(anyInt(), any(Instant.class));
        assertThat(recordedAudit().action()).isEqualTo("USER_ENABLED");
    }

    // -------------------------------------------------------------- export

    @Test
    void exportNeutralizesFormulasNamesTheFileAfterTheBankAndAuditsTheRowCount() {
        AppUser hostile = user(1, Bank.DEFAULT_BANK_ID, Role.READ_ONLY);
        hostile.setDisplayName("=HYPERLINK(\"http://evil.example\")");
        when(users.listByBank(Bank.DEFAULT_BANK_ID)).thenReturn(List.of(hostile));
        when(userMfa.enabledUserIds(anyCollection())).thenReturn(Set.of());
        Bank bank = new Bank(Bank.DEFAULT_BANK_CODE, "Default Bank");
        bank.setBankId(Bank.DEFAULT_BANK_ID);
        when(banks.findById(Bank.DEFAULT_BANK_ID)).thenReturn(Optional.of(bank));

        UserAdminUseCase.CsvExport export = useCase.exportUsers();

        assertThat(export.fileName())
                .isEqualTo("users-DEFAULT-" + LocalDate.now() + ".csv");
        String csv = new String(export.content(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith(
                "Email,DisplayName,Role,Disabled,LastLoginAt,MfaEnabled,AuthProvider,CreatedDate");
        // The formula is neutralized exactly like the data exports do it.
        assertThat(csv).contains("'=HYPERLINK").doesNotContain(",=HYPERLINK");
        AuditEventSpec recorded = recordedAudit();
        assertThat(recorded.action()).isEqualTo("USER_LIST_EXPORTED");
        assertThat(String.valueOf(recorded.newValues())).contains("rows=1");
    }

    // ------------------------------------------- ws21 role policy + quorum

    @Test
    void aCustomerBankNeverHandsOutPlatformOrMachineRoles() {
        for (Role role : new Role[] {Role.PLATFORM_ADMIN, Role.API_CLIENT}) {
            assertThatThrownBy(() -> useCase.createApprovedUser("x@example.com", "X", role,
                    "{argon2}$h", Bank.DEFAULT_BANK_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ADMIN, APPROVER, READ_WRITE or READ_ONLY");
            assertThatThrownBy(() -> useCase.changeRole(7, role))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(auth, never()).createUserWithHash(any(), any(), any(), anyInt(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void thePlatformBankOnlyHandsOutPlatformAdmin() {
        UserDto created = new UserDto(9, "ops@example.com", "Ops", "{}", "PLATFORM_ADMIN",
                PLATFORM_BANK, true);
        when(auth.createUserWithHash("ops@example.com", "{argon2}$h", "Ops", PLATFORM_BANK,
                Role.PLATFORM_ADMIN, true)).thenReturn(created);

        assertThat(platformUseCase.createApprovedUser("ops@example.com", "Ops",
                Role.PLATFORM_ADMIN, "{argon2}$h", PLATFORM_BANK)).isSameAs(created);
        for (Role bankRole : new Role[] {Role.ADMIN, Role.APPROVER, Role.READ_WRITE,
                Role.READ_ONLY}) {
            assertThatThrownBy(() -> platformUseCase.createApprovedUser("b@example.com", "B",
                    bankRole, "{argon2}$h", PLATFORM_BANK))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("use PLATFORM_ADMIN");
        }
    }

    @Test
    void disablingAPlatformAdminMustLeaveTwoActive() {
        AppUser target = user(8, PLATFORM_BANK, Role.PLATFORM_ADMIN);
        when(users.getById(8)).thenReturn(Optional.of(target));
        when(users.countActiveByRole(PLATFORM_BANK, Role.PLATFORM_ADMIN)).thenReturn(2L);

        assertThatThrownBy(() -> platformUseCase.setDisabled(8, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 2 active platform administrators");
        verify(users, never()).save(any(AppUser.class));

        // With a third active platform admin, one may go.
        when(users.countActiveByRole(PLATFORM_BANK, Role.PLATFORM_ADMIN)).thenReturn(3L);
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        assertThat(platformUseCase.setDisabled(8, true).disabled()).isTrue();
    }

    private AuditEventSpec recordedAudit() {
        ArgumentCaptor<AuditEventSpec> captor = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }
}
