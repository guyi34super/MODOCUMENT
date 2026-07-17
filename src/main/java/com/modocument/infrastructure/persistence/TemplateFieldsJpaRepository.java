package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.TemplateFields;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateFieldsJpaRepository extends JpaRepository<TemplateFields, Integer> {
}
