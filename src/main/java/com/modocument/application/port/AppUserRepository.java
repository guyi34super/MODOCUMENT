package com.modocument.application.port;

import com.modocument.domain.entity.AppUser;

import java.util.Optional;

public interface AppUserRepository {

    Optional<AppUser> getById(int id);

    Optional<AppUser> getByEmail(String email);

    boolean existsByEmail(String email);

    AppUser save(AppUser entity);
}
