package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.Template;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateJpaRepository extends JpaRepository<Template, Integer> {
}
