package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserJpaRepository extends JpaRepository<AppUser, Integer> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** OIDC identity link (ws07): returning users match by (provider, subject) first. */
    Optional<AppUser> findByAuthProviderAndProviderSubject(String authProvider,
                                                           String providerSubject);

    /** ws09 user administration: bank-scoped listing (AppUsers is not under RLS). */
    List<AppUser> findByBankIdOrderByEmailAsc(int bankId);

    /** ws09 last-admin guard: enabled ADMIN accounts remaining in the bank. */
    long countByBankIdAndRoleAndDisabledFalse(int bankId, Role role);
}
