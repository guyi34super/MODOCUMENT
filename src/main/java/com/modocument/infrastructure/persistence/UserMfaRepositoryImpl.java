package com.modocument.infrastructure.persistence;

import com.modocument.application.port.UserMfaRepository;
import com.modocument.domain.entity.UserMfa;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserMfaRepositoryImpl implements UserMfaRepository {

    private final UserMfaJpaRepository jpa;

    public UserMfaRepositoryImpl(UserMfaJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<UserMfa> getByUserId(int userId) {
        return jpa.findById(userId);
    }

    @Override
    public boolean isEnabled(int userId) {
        return jpa.existsByUserIdAndEnabledAtNotNull(userId);
    }

    @Override
    public UserMfa save(UserMfa entity) {
        return jpa.save(entity);
    }

    @Override
    public void deleteByUserId(int userId) {
        jpa.deleteById(userId);
    }
}
