package com.modocument.infrastructure.persistence;

import com.modocument.application.port.AppUserRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AppUserRepositoryImpl implements AppUserRepository {

    private final AppUserJpaRepository jpa;

    public AppUserRepositoryImpl(AppUserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<AppUser> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public Optional<AppUser> getByEmail(String email) {
        return jpa.findByEmailIgnoreCase(email);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpa.existsByEmailIgnoreCase(email);
    }

    @Override
    public AppUser save(AppUser entity) {
        return jpa.save(entity);
    }

    @Override
    public List<AppUser> listByBank(int bankId) {
        return jpa.findByBankIdOrderByEmailAsc(bankId);
    }

    @Override
    public long countActiveAdmins(int bankId) {
        return jpa.countByBankIdAndRoleAndDisabledFalse(bankId, Role.ADMIN);
    }

    @Override
    public long countActiveByRole(int bankId, Role role) {
        return jpa.countByBankIdAndRoleAndDisabledFalse(bankId, role);
    }

    @Override
    public java.util.Map<Integer, AppUser> getByIds(java.util.Collection<Integer> ids) {
        java.util.Map<Integer, AppUser> byId = new java.util.LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return byId;
        }
        for (AppUser user : jpa.findAllById(ids)) {
            byId.put(user.getAppUserId(), user);
        }
        return byId;
    }

    @Override
    public List<AppUser> listByBankAndIds(int bankId, java.util.Collection<Integer> ids) {
        return ids == null || ids.isEmpty() ? List.of() : jpa.findByBankIdAndAppUserIdIn(bankId, ids);
    }
}
