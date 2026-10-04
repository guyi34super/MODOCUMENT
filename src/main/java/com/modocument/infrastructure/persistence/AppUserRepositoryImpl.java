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
}
