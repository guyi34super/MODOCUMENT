package com.modocument.application.port;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;

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

    /** How many enabled (not disabled) accounts with this role the bank has (ws21). */
    long countActiveByRole(int bankId, Role role);

    /**
     * ws28: several users by id in one statement, keyed by id (unknown ids
     * absent). AppUsers is not under RLS: callers resolve only ids taken
     * from rows of their own bank (makers, owners, submitters).
     */
    default java.util.Map<Integer, AppUser> getByIds(java.util.Collection<Integer> ids) {
        java.util.Map<Integer, AppUser> byId = new java.util.LinkedHashMap<>();
        for (Integer id : ids) {
            getById(id).ifPresent(user -> byId.put(id, user));
        }
        return byId;
    }

    /** ws28: the users of ONE bank among {@code ids} — the bank filter is the scoping. */
    default List<AppUser> listByBankAndIds(int bankId, java.util.Collection<Integer> ids) {
        return getByIds(ids).values().stream().filter(user -> user.getBankId() == bankId).toList();
    }
}
