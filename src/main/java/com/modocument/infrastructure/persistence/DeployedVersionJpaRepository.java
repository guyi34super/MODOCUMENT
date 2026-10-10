package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.DeployedVersion;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ws24 DeployedVersion (V240, non-tenant): written by DeployedVersionRecorder
 * (insert-only, first task wins), read by SystemVersionService.
 */
public interface DeployedVersionJpaRepository extends JpaRepository<DeployedVersion, String> {
}
