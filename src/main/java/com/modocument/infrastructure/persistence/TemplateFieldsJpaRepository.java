package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.TemplateFields;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TemplateFieldsJpaRepository extends JpaRepository<TemplateFields, Integer> {

    List<TemplateFields> findByTemplateId(int templateId);
}
