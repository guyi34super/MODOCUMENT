package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.UserMfa;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface UserMfaJpaRepository extends JpaRepository<UserMfa, Integer> {

    boolean existsByUserIdAndEnabledAtNotNull(int userId);

    List<UserMfa> findByUserIdInAndEnabledAtNotNull(Collection<Integer> userIds);
}
