package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RevokedTokenJpaRepository extends JpaRepository<RevokedToken, UUID> {
}
