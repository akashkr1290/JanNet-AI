package com.jannetai.backend.repository;

import com.jannetai.backend.entity.AssistantSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/** JanNet Sahayak sessions (V38). Messages are deleted with their session by the database (ON DELETE CASCADE). */
@Repository
public interface AssistantSessionRepository extends JpaRepository<AssistantSession, Long> {

    /** A session is only ever found together with its owner - another user's id finds nothing. */
    Optional<AssistantSession> findByPublicIdAndUser_UserId(String publicId, Long userId);

    /** 90-day retention (AssistantRetentionJob). */
    @Modifying
    @Query("DELETE FROM AssistantSession s WHERE s.lastMessageAt < :cutoff")
    int deleteInactiveSince(@Param("cutoff") LocalDateTime cutoff);

    /** Account erasure (PersonalDataService). */
    @Modifying
    @Query("DELETE FROM AssistantSession s WHERE s.user.userId = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
