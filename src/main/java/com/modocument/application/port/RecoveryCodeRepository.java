package com.modocument.application.port;

import com.modocument.domain.entity.RecoveryCode;

import java.util.List;

/** Persistence port for MFA recovery codes (hashes only, ten per enrolment). */
public interface RecoveryCodeRepository {

    List<RecoveryCode> getUnusedByUserId(int userId);

    RecoveryCode save(RecoveryCode entity);

    /** Drops every code of the user — on re-enrolment and on MFA disable. */
    void deleteByUserId(int userId);
}
