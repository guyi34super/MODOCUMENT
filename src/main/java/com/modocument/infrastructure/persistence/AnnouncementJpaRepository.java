package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.Announcement;
import com.modocument.domain.model.AnnouncementKind;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * ws24 announcements (V241). Every query names the owning bank explicitly
 * (the platform bank) on top of row level security, so the H2 suite — which
 * has no RLS — filters the same way PostgreSQL does.
 */
public interface AnnouncementJpaRepository extends JpaRepository<Announcement, Integer> {

    /** The cache's working set: not archived and not yet ended, newest start first. */
    @Query("SELECT a FROM Announcement a WHERE a.bankId = :bankId AND a.archived = false "
            + "AND a.endsAt > :now ORDER BY a.startsAt DESC, a.announcementId DESC")
    List<Announcement> findLive(@Param("bankId") int bankId, @Param("now") Instant now,
                                Pageable page);

    /** The platform console's list, newest first (archived included). */
    @Query("SELECT a FROM Announcement a WHERE a.bankId = :bankId "
            + "ORDER BY a.createdAt DESC, a.announcementId DESC")
    List<Announcement> findForConsole(@Param("bankId") int bankId, Pageable page);

    Optional<Announcement> findByAnnouncementIdAndBankId(int announcementId, int bankId);

    /** Whether a live RELEASE banner for this version exists (the recorder skips then). */
    boolean existsByBankIdAndKindAndReleaseVersionAndArchivedFalse(int bankId, AnnouncementKind kind,
                                                                   String releaseVersion);
}
