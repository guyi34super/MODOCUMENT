package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.ProcessStep;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessStepJpaRepository extends JpaRepository<ProcessStep, Integer> {
}
