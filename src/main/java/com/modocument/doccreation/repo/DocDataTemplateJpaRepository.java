package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocDataTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** V330: data templates (validation of document creation); every call names the caller's bank. */
public interface DocDataTemplateJpaRepository extends JpaRepository<DocDataTemplate, Integer> {

    List<DocDataTemplate> findByBankIdAndOwnerUserIdOrderByNameAscDocDataTemplateIdAsc(int bankId, int ownerUserId);

    Optional<DocDataTemplate> findByDocDataTemplateIdAndBankIdAndOwnerUserId(int id, int bankId, int ownerUserId);

    Optional<DocDataTemplate> findByDocDataTemplateIdAndBankId(int id, int bankId);
}
