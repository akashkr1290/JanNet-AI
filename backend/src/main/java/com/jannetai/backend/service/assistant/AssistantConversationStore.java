package com.jannetai.backend.service.assistant;

import com.jannetai.backend.entity.AssistantMessage;
import com.jannetai.backend.entity.AssistantSession;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.repository.AssistantMessageRepository;
import com.jannetai.backend.repository.AssistantSessionRepository;
import com.jannetai.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JanNet Sahayak's conversation log (assistant_sessions / assistant_messages,
 * V38). Kept apart from AssistantService so the log is written in short
 * transactions and no database connection is held while ai-service is called.
 * Only redacted text reaches this class.
 */
@Service
@RequiredArgsConstructor
public class AssistantConversationStore {

    private static final int MAX_TEXT = 2000;
    private static final int MAX_TOOLS = 255;

    private final AssistantSessionRepository sessionRepository;
    private final AssistantMessageRepository messageRepository;
    private final UserRepository userRepository;

    /**
     * The user's own session with this id, or a new one. A missing, expired or
     * another user's id silently starts a new session (nothing is revealed).
     */
    @Transactional
    public AssistantSession openSession(User user, String publicId, String language) {
        if (publicId != null && !publicId.isBlank()) {
            var existing = sessionRepository.findByPublicIdAndUser_UserId(publicId.trim(), user.getUserId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        AssistantSession session = AssistantSession.builder()
                .publicId(UUID.randomUUID().toString())
                .user(userRepository.getReferenceById(user.getUserId()))
                .language(language)
                .messageCount(0)
                .lastMessageAt(LocalDateTime.now())
                .build();
        return sessionRepository.save(session);
    }

    /** Stores the user's (redacted) message and the assistant's reply, and moves the session's clock. */
    @Transactional
    public void record(Long sessionId, String language, String userText, AssistantService.Outcome outcome) {
        AssistantSession session = sessionRepository.findById(sessionId).orElseThrow();
        messageRepository.save(AssistantMessage.builder()
                .session(session)
                .sender(AssistantMessage.USER)
                .messageText(cut(userText, MAX_TEXT))
                .build());
        messageRepository.save(AssistantMessage.builder()
                .session(session)
                .sender(AssistantMessage.ASSISTANT)
                .messageText(cut(outcome.reply(), MAX_TEXT))
                .intent(outcome.intent().name())
                .answerSource(outcome.source())
                .toolsUsed(outcome.tools().isEmpty() ? null : cut(String.join(",", outcome.tools()), MAX_TOOLS))
                .complaintId(outcome.complaintId())
                .build());
        session.setMessageCount(session.getMessageCount() + 2);
        session.setLanguage(language);
        session.setLastMessageAt(LocalDateTime.now());
    }

    private static String cut(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
