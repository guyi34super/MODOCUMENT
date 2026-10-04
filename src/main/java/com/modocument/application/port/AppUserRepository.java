package com.modocument.application.port;

import com.modocument.domain.entity.AppUser;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository {

    Optional<AppUser> getById(int id);

    Optional<AppUser> getByEmail(String email);

    boolean existsByEmail(String email);

    AppUser save(AppUser entity);

    /**
     * Every user of one bank, ordered by email (ws09 user administration).
     * AppUsers is deliberately not under RLS — THIS filter is the bank
     * scoping, so callers must always pass the caller's own bank.
     */
    List<AppUser> listByBank(int bankId);

    /** How many enabled (not disabled) ADMIN accounts the bank still has. */
    long countActiveAdmins(int bankId);
}
