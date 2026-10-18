package com.jannetai.backend.repository;

import com.jannetai.backend.entity.AssistantMessage;
import com.jannetai.backend.entity.AssistantSession;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.HelpArticle;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JanNet Sahayak (V38) against real MySQL + Flyway: the seeded help articles,
 * owner-only session lookup, 90-day retention and account erasure (messages go
 * with their session through ON DELETE CASCADE).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AssistantRepositoryTest {

    @Autowired private TestEntityManager entityManager;
    @Autowired private AssistantSessionRepository sessionRepository;
    @Autowired private AssistantMessageRepository messageRepository;
    @Autowired private HelpArticleRepository helpArticleRepository;
    @Autowired private ComplaintRepository complaintRepository;

    private User persistCitizen(String mobile) {
        return entityManager.persistAndFlush(User.builder().fullName("Citizen " + mobile).mobileNumber(mobile)
                .passwordHash("hash").role(Role.CITIZEN).reputationScore(100).status(UserStatus.ACTIVE)
                .failedLoginCount(0).build());
    }

    private AssistantSession persistSession(User user, LocalDateTime lastMessageAt) {
        AssistantSession s = entityManager.persistAndFlush(AssistantSession.builder()
                .publicId(UUID.randomUUID().toString()).user(user).language("HI").messageCount(2)
                .lastMessageAt(lastMessageAt).build());
        entityManager.persistAndFlush(AssistantMessage.builder().session(s).sender(AssistantMessage.USER)
                .messageText("meri shikayat kahan hai").build());
        entityManager.persistAndFlush(AssistantMessage.builder().session(s).sender(AssistantMessage.ASSISTANT)
                .messageText("reply").intent("MY_COMPLAINTS").answerSource("RULE").toolsUsed("complaint_list").build());
        return s;
    }

    private long messagesOf(Long sessionId) {
        return ((Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM assistant_messages WHERE session_id = :id")
                .setParameter("id", sessionId).getSingleResult()).longValue();
    }

    @Test
    void twentyArticlesAreSeededInEachOfTheSevenLanguages() {
        for (String lang : List.of("EN", "HI", "MR", "BN", "TA", "TE", "KN")) {
            List<HelpArticle> rows = helpArticleRepository.findByLanguageInAndIsActiveTrueOrderBySortOrderAsc(List.of(lang));
            assertThat(rows).as(lang).hasSize(20);
            assertThat(rows.get(0).getSlug()).isEqualTo("how-to-report");
        }
        assertThat(helpArticleRepository.findByLanguageInAndIsActiveTrueOrderBySortOrderAsc(List.of("HI", "EN"))).hasSize(40);
    }

    @Test
    void aSessionIsOnlyFoundWithItsOwner() {
        User owner = persistCitizen("+919100000001");
        User other = persistCitizen("+919100000002");
        AssistantSession s = persistSession(owner, LocalDateTime.now());

        assertThat(sessionRepository.findByPublicIdAndUser_UserId(s.getPublicId(), owner.getUserId())).isPresent();
        assertThat(sessionRepository.findByPublicIdAndUser_UserId(s.getPublicId(), other.getUserId())).isEmpty();
    }

    @Test
    void retentionDeletesOldConversationsWithTheirMessages() {
        User user = persistCitizen("+919100000003");
        AssistantSession old = persistSession(user, LocalDateTime.now().minusDays(91));
        AssistantSession recent = persistSession(user, LocalDateTime.now().minusDays(5));
        entityManager.clear();

        int deleted = sessionRepository.deleteInactiveSince(LocalDateTime.now().minusDays(90));

        assertThat(deleted).isEqualTo(1);
        assertThat(sessionRepository.findById(old.getSessionId())).isEmpty();
        assertThat(sessionRepository.findById(recent.getSessionId())).isPresent();
        assertThat(messagesOf(old.getSessionId())).isZero();
        assertThat(messagesOf(recent.getSessionId())).isEqualTo(2);
    }

    @Test
    void erasureDeletesAllOfTheUsersConversations() {
        User user = persistCitizen("+919100000004");
        User other = persistCitizen("+919100000005");
        AssistantSession a = persistSession(user, LocalDateTime.now());
        AssistantSession b = persistSession(user, LocalDateTime.now().minusDays(1));
        AssistantSession keep = persistSession(other, LocalDateTime.now());
        entityManager.clear();

        assertThat(sessionRepository.deleteAllForUser(user.getUserId())).isEqualTo(2);
        assertThat(messagesOf(a.getSessionId()) + messagesOf(b.getSessionId())).isZero();
        assertThat(sessionRepository.findById(keep.getSessionId())).isPresent();
    }

    @Test
    void complaintIsFoundByItsReferenceNumber() {
        User citizen = persistCitizen("+919100000006");
        entityManager.persistAndFlush(Complaint.builder().referenceNumber("JN-2026-000777").citizen(citizen)
                .category(ComplaintCategory.POTHOLE).status(ComplaintStatus.SUBMITTED).corroborationCount(1)
                .isEscalated(false).isReopened(false).build());

        assertThat(complaintRepository.findByReferenceNumber("JN-2026-000777")).isPresent();
        assertThat(complaintRepository.findByReferenceNumber("JN-2026-000778")).isEmpty();
        assertThat(messageRepository.count()).isGreaterThanOrEqualTo(0);
    }
}
