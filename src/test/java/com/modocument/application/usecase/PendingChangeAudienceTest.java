package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.api.security.PermissionChecker;
import com.modocument.application.dto.PendingChangeDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PendingChangeRepository;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.security.ChangeAudience;
import com.modocument.application.security.SodPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.PendingChangeStatus;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.PasswordChangeRequiredGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who sees and who decides every maker/checker change type (ws10 + phase 2,
 * contracts §2), table-driven over ALL {@link ChangeType}s with the real
 * role matrix and the real {@link PermissionChecker} platform guard:
 * <ul>
 *   <li>a bank type is listed to — and decided by — the other
 *       CHANGE_APPROVE holders of the maker's bank (ADMIN, APPROVER);</li>
 *   <li>a platform type only to a second platform admin;</li>
 *   <li>nobody in another bank sees either (404 on decide);</li>
 *   <li>the maker always sees their own row and can never decide it
 *       (SodPolicy, 409).</li>
 * </ul>
 */
class PendingChangeAudienceTest {

    private static final int PLATFORM_BANK = 1;
    private static final int BANK_A = 10;
    private static final int BANK_B = 20;

    /** The expected audience of every change type (the contract, written out). */
    private static final Map<ChangeType, ChangeAudience> EXPECTED = new EnumMap<>(Map.ofEntries(
            Map.entry(ChangeType.USER_CREATE, ChangeAudience.BANK),
            Map.entry(ChangeType.USER_ROLE_CHANGE, ChangeAudience.BANK),
            Map.entry(ChangeType.USER_DISABLE, ChangeAudience.BANK),
            Map.entry(ChangeType.TEMPLATE_UPDATE, ChangeAudience.BANK),
            Map.entry(ChangeType.TEMPLATE_DELETE, ChangeAudience.BANK),
            // Phase 2.
            Map.entry(ChangeType.SETTING_UPDATE, ChangeAudience.BANK),
            Map.entry(ChangeType.PLATFORM_SETTING_UPDATE, ChangeAudience.PLATFORM),
            Map.entry(ChangeType.USER_PASSWORD_RESET, ChangeAudience.BANK),
            Map.entry(ChangeType.USER_MFA_RESET, ChangeAudience.BANK),
            Map.entry(ChangeType.BANK_CREATE, ChangeAudience.PLATFORM),
            Map.entry(ChangeType.BANK_SUSPEND, ChangeAudience.PLATFORM),
            Map.entry(ChangeType.BANK_ADMIN_ADD, ChangeAudience.PLATFORM),
            Map.entry(ChangeType.INTEGRATION_CONNECTION_CHANGE, ChangeAudience.BANK),
            Map.entry(ChangeType.INTEGRATION_FLOW_ENABLE, ChangeAudience.BANK),
            Map.entry(ChangeType.API_CLIENT_CREATE, ChangeAudience.BANK),
            Map.entry(ChangeType.AGENT_REGISTER, ChangeAudience.BANK),
            Map.entry(ChangeType.EGRESS_RULE_APPROVE, ChangeAudience.PLATFORM)));

    /** A signed-in user of the test world. */
    private enum Persona {
        PLATFORM_ADMIN_1(101, PLATFORM_BANK, Role.PLATFORM_ADMIN),
        PLATFORM_ADMIN_2(102, PLATFORM_BANK, Role.PLATFORM_ADMIN),
        ADMIN_A1(201, BANK_A, Role.ADMIN),
        ADMIN_A2(202, BANK_A, Role.ADMIN),
        APPROVER_A(203, BANK_A, Role.APPROVER),
        WRITER_A(204, BANK_A, Role.READ_WRITE),
        READER_A(205, BANK_A, Role.READ_ONLY),
        ADMIN_B(301, BANK_B, Role.ADMIN);

        final int userId;
        final int bankId;
        final Role role;

        Persona(int userId, int bankId, Role role) {
            this.userId = userId;
            this.bankId = bankId;
            this.role = role;
        }
    }

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PendingChangeRepository changes = mock(PendingChangeRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PlatformBankLookup platformBanks = () -> OptionalInt.of(PLATFORM_BANK);
    private final PasswordChangeRequiredGate passwordGate = mock(PasswordChangeRequiredGate.class);
    private final Map<ChangeType, PendingChangeApplier> appliers = new EnumMap<>(ChangeType.class);
    private final List<PendingChange> stored = new ArrayList<>();

    private PendingChangeUseCase useCase;
    private Persona caller;

    @BeforeEach
    void setUp() {
        for (ChangeType type : ChangeType.values()) {
            PendingChangeApplier applier = mock(PendingChangeApplier.class);
            when(applier.type()).thenReturn(type);
            appliers.put(type, applier);
        }
        when(passwordGate.callerMustChange()).thenReturn(false);
        when(currentUser.requireUserId()).thenAnswer(ignored -> caller.userId);
        when(currentUser.requireBankId()).thenAnswer(ignored -> caller.bankId);
        when(currentUser.role()).thenAnswer(ignored -> caller.role);
        for (Persona persona : Persona.values()) {
            AppUser user = new AppUser();
            user.setAppUserId(persona.userId);
            user.setBankId(persona.bankId);
            user.setEmail(persona.name().toLowerCase() + "@example.com");
            when(users.getById(persona.userId)).thenReturn(Optional.of(user));
        }
        when(changes.listByBank(anyInt())).thenAnswer(call -> stored.stream()
                .filter(row -> row.getBankId() == (int) call.getArgument(0)).toList());
        when(changes.listByBankAndStatus(anyInt(), any())).thenAnswer(call -> stored.stream()
                .filter(row -> row.getBankId() == (int) call.getArgument(0)
                        && row.getStatus() == call.getArgument(1)).toList());
        when(changes.getById(anyInt())).thenAnswer(call -> stored.stream()
                .filter(row -> row.getPendingChangeId() == (int) call.getArgument(0))
                .findFirst());
        when(changes.save(any())).thenAnswer(call -> call.getArgument(0));

        PermissionChecker perm = new PermissionChecker(currentUser, passwordGate, platformBanks);
        useCase = newUseCase(perm);
    }

    private PendingChangeUseCase newUseCase(PermissionChecker perm) {
        return new PendingChangeUseCase(currentUser, users, changes, audit, perm,
                new SodPolicy(), platformBanks, new ObjectMapper(),
                List.copyOf(appliers.values()), mock(PlatformTransactionManager.class));
    }

    @Test
    void everyChangeTypeHasItsExpectedAudience() {
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(ChangeType.values());
        for (ChangeType type : ChangeType.values()) {
            assertThat(ChangeAudience.of(type)).as(type.name()).isEqualTo(EXPECTED.get(type));
            if (EXPECTED.get(type) == ChangeAudience.PLATFORM) {
                // A platform type is made with a platform permission, so its
                // checker check ("holds the maker permission") pins the
                // platform bank through PermissionChecker.
                assertThat(type.makerPermission().name()).startsWith("PLATFORM_");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ChangeType.class)
    void listsAndDecidesEachTypeForExactlyTheRightCheckers(ChangeType type) {
        boolean platform = EXPECTED.get(type) == ChangeAudience.PLATFORM;
        Persona maker = platform ? Persona.PLATFORM_ADMIN_1 : Persona.ADMIN_A1;
        Set<Persona> checkers = platform
                ? EnumSet.of(Persona.PLATFORM_ADMIN_2)
                : EnumSet.of(Persona.ADMIN_A2, Persona.APPROVER_A);

        for (Persona persona : Persona.values()) {
            PendingChange row = store(type, maker.bankId, maker);
            boolean sameBank = persona.bankId == maker.bankId;
            boolean checker = checkers.contains(persona);
            caller = persona;
            String context = type + " as " + persona;

            // --- list
            if (!holdsAnyQueuePermission(persona)) {
                assertThatThrownBy(() -> useCase.list(null)).as(context)
                        .isInstanceOf(AccessDeniedException.class);
            } else {
                List<Integer> listed = useCase.list("REQUESTED").stream()
                        .map(PendingChangeDto::pendingChangeId).toList();
                boolean expectListed = sameBank && (checker || persona == maker);
                assertThat(listed.contains(row.getPendingChangeId()))
                        .as(context + " listed").isEqualTo(expectListed);
            }

            // --- approve (and reject on a fresh row)
            PendingChangeApplier applier = appliers.get(type);
            reset(applier);
            when(applier.type()).thenReturn(type);
            if (!sameBank) {
                assertThatThrownBy(() -> useCase.approve(row.getPendingChangeId(), null))
                        .as(context).isInstanceOf(NoSuchElementException.class);
            } else if (persona == maker) {
                assertThatThrownBy(() -> useCase.approve(row.getPendingChangeId(), null))
                        .as(context).isInstanceOf(SodPolicy.SodViolationException.class);
            } else if (!checker) {
                assertThatThrownBy(() -> useCase.approve(row.getPendingChangeId(), null))
                        .as(context).isInstanceOf(AccessDeniedException.class);
                PendingChange other = store(type, maker.bankId, maker);
                assertThatThrownBy(() -> useCase.reject(other.getPendingChangeId(), null))
                        .as(context).isInstanceOf(AccessDeniedException.class);
            } else {
                assertThat(useCase.approve(row.getPendingChangeId(), "ok").status())
                        .as(context).isEqualTo("APPLIED");
                verify(applier).apply(row);
                PendingChange other = store(type, maker.bankId, maker);
                assertThat(useCase.reject(other.getPendingChangeId(), "no").status())
                        .as(context).isEqualTo("REJECTED");
                continue;
            }
            verify(applier, never()).apply(any());
            assertThat(row.getStatus()).as(context).isEqualTo(PendingChangeStatus.REQUESTED);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ChangeType.class, names = {"PLATFORM_SETTING_UPDATE", "BANK_CREATE",
            "BANK_SUSPEND", "BANK_ADMIN_ADD", "EGRESS_RULE_APPROVE"})
    void aPlatformTypeOutsideThePlatformBankIsDecidedByNobody(ChangeType type) {
        // Defense in depth: such a row cannot be made (the maker permission
        // needs the platform bank), but if one existed in a customer bank its
        // approvers must not be able to apply a platform change.
        PendingChange row = store(type, BANK_A, Persona.ADMIN_A1);
        caller = Persona.ADMIN_A2;

        assertThat(useCase.list(null)).isEmpty();
        assertThatThrownBy(() -> useCase.approve(row.getPendingChangeId(), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("second platform administrator");
        verify(appliers.get(type), never()).apply(any());
    }

    @ParameterizedTest
    @EnumSource(ChangeType.class)
    void aMakerWithoutChangeApproveListsOnlyTheirOwnRows(ChangeType type) {
        // Listing must not depend on a maker role also holding
        // CHANGE_APPROVE: a caller holding ONLY the type's maker permission
        // sees their own submission and nothing else.
        PermissionChecker makerOnly = mock(PermissionChecker.class);
        when(makerOnly.has(anyString())).thenAnswer(call ->
                type.makerPermission().name().equals(call.getArgument(0)));
        PendingChangeUseCase restricted = newUseCase(makerOnly);
        caller = Persona.WRITER_A;
        PendingChange own = store(type, BANK_A, Persona.WRITER_A);
        PendingChange others = store(type, BANK_A, Persona.ADMIN_A1);

        List<Integer> listed = restricted.list(null).stream()
                .map(PendingChangeDto::pendingChangeId).toList();

        assertThat(listed).contains(own.getPendingChangeId())
                .doesNotContain(others.getPendingChangeId());
        assertThatThrownBy(() -> restricted.approve(others.getPendingChangeId(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static boolean holdsAnyQueuePermission(Persona persona) {
        Set<Permission> queue = EnumSet.of(Permission.CHANGE_APPROVE);
        for (ChangeType type : ChangeType.values()) {
            queue.add(type.makerPermission());
        }
        return queue.stream().anyMatch(permission ->
                com.modocument.application.security.RolePermissions.isGranted(persona.role,
                        permission));
    }

    private PendingChange store(ChangeType type, int bankId, Persona maker) {
        PendingChange row = new PendingChange();
        row.setPendingChangeId(stored.size() + 1);
        row.setBankId(bankId);
        row.setChangeType(type);
        row.setTargetEntityType(type.targetEntityType());
        row.setPayloadJson("{}");
        row.setStatus(PendingChangeStatus.REQUESTED);
        row.setMakerUserId(maker.userId);
        row.setRequestedAt(LocalDateTime.now());
        stored.add(row);
        return row;
    }
}
