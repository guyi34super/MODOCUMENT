package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AppUserJpaRepository extends JpaRepository<AppUser, Integer> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** ws26 leaver check: the user exists, belongs to the bank and is not disabled. */
    boolean existsByAppUserIdAndBankIdAndDisabledFalse(int appUserId, int bankId);

    /** OIDC identity link (ws07): returning users match by (provider, subject) first. */
    Optional<AppUser> findByAuthProviderAndProviderSubject(String authProvider,
                                                           String providerSubject);

    /** ws09 user administration: bank-scoped listing (AppUsers is not under RLS). */
    List<AppUser> findByBankIdOrderByEmailAsc(int bankId);

    /** ws28: the users of one bank among the given ids (AppUsers is not under RLS). */
    List<AppUser> findByBankIdAndAppUserIdIn(int bankId, java.util.Collection<Integer> ids);

    /** ws09 last-admin guard: enabled ADMIN accounts remaining in the bank. */
    long countByBankIdAndRoleAndDisabledFalse(int bankId, Role role);

    /**
     * ws21 bank suspension: bumps EVERY account of the bank in one statement,
     * so the RevocationValidator rejects each of their outstanding access
     * tokens on its next request. Returns the number of accounts touched.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AppUser u set u.tokenVersion = u.tokenVersion + 1, u.updatedDate = :now"
            + " where u.bankId = :bankId")
    int bumpTokenVersionForBank(@Param("bankId") int bankId, @Param("now") LocalDateTime now);

    // ---- Admin dashboard counts (phase 2, ws22). AppUsers and UserMfa are
    // NOT under RLS: every query names the bank explicitly, and returns
    // numbers only — never a row.

    /** Rows of [Role, disabled (Boolean), count (Long)] for one bank. */
    @Query("select u.role, u.disabled, count(u) from AppUser u"
            + " where u.bankId = :bankId group by u.role, u.disabled")
    List<Object[]> countByRoleAndDisabled(@Param("bankId") int bankId);

    /** Accounts whose login lockout is still in force at {@code now}. */
    long countByBankIdAndLockedUntilAfter(int bankId, LocalDateTime now);

    /** Accounts still holding an admin-issued temporary password. */
    long countByBankIdAndMustChangePasswordTrue(int bankId);

    /** Accounts that signed in at or after {@code since}. */
    long countByBankIdAndLastLoginAtGreaterThanEqual(int bankId, LocalDateTime since);

    /** Accounts of the bank with an ACTIVATED TOTP enrolment. */
    @Query("select count(u) from AppUser u where u.bankId = :bankId and exists"
            + " (select m.userId from UserMfa m where m.userId = u.appUserId"
            + " and m.enabledAt is not null)")
    long countWithMfaEnabled(@Param("bankId") int bankId);

    /** Enabled accounts of one role WITHOUT an activated TOTP enrolment. */
    @Query("select count(u) from AppUser u where u.bankId = :bankId and u.role = :role"
            + " and u.disabled = false and not exists"
            + " (select m.userId from UserMfa m where m.userId = u.appUserId"
            + " and m.enabledAt is not null)")
    long countActiveWithoutMfa(@Param("bankId") int bankId, @Param("role") Role role);
}
