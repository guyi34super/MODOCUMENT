package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.Template;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateJpaRepository extends JpaRepository<Template, Integer> {

    java.util.Optional<Template> findByTemplateIdAndOwnerUserId(int templateId, int ownerUserId);

    java.util.List<Template> findByOwnerUserId(int ownerUserId);

    /** Admin dashboard (phase 2): every template of one bank, whoever owns it. */
    long countByBankId(int bankId);
}
