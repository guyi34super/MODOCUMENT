package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.UserMfa;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMfaJpaRepository extends JpaRepository<UserMfa, Integer> {

    boolean existsByUserIdAndEnabledAtNotNull(int userId);
}
