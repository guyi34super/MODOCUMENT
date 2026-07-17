package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.TemplateDataType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateDataTypeJpaRepository extends JpaRepository<TemplateDataType, Integer> {
}
