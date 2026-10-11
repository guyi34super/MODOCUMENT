package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.ApiClientSecret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** ws26 client secrets (V260): Argon2id hashes, current + rotation overlap. */
public interface ApiClientSecretJpaRepository extends JpaRepository<ApiClientSecret, Integer> {

    @Query("SELECT s FROM ApiClientSecret s WHERE s.apiClientId = :apiClientId "
            + "AND s.bankId = :bankId ORDER BY s.apiClientSecretId DESC")
    List<ApiClientSecret> findForClient(@Param("apiClientId") int apiClientId,
                                        @Param("bankId") int bankId);
}
