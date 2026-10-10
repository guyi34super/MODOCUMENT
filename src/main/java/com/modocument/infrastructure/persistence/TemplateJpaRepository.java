package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.Template;
import com.modocument.domain.model.TemplateVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TemplateJpaRepository extends JpaRepository<Template, Integer> {

    /**
     * The template-visibility read predicate (ws22): the caller's own
     * template, or a BANK-shared template of the caller's bank. The bank is
     * an explicit parameter (never "any bank"), so the rule holds on H2 too;
     * RLS on Template backs it on PostgreSQL.
     */
    @Query("select t from Template t where t.templateId = :id and (t.ownerUserId = :userId"
            + " or (t.visibility = com.modocument.domain.model.TemplateVisibility.BANK"
            + " and t.bankId = :bankId))")
    Optional<Template> findAccessible(@Param("id") int id, @Param("userId") int userId,
                                      @Param("bankId") int bankId);

    /** Every template {@link #findAccessible} would return for the caller, oldest first. */
    @Query("select t from Template t where t.ownerUserId = :userId"
            + " or (t.visibility = com.modocument.domain.model.TemplateVisibility.BANK"
            + " and t.bankId = :bankId) order by t.templateId")
    List<Template> findAccessible(@Param("userId") int userId, @Param("bankId") int bankId);

    /** Admin dashboard (phase 2): every template of one bank, whoever owns it. */
    long countByBankId(int bankId);

    /** Admin dashboard (ws22): templates of one bank by visibility (private / shared). */
    long countByBankIdAndVisibility(int bankId, TemplateVisibility visibility);
}
