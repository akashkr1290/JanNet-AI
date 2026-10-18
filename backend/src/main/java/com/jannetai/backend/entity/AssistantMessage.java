package com.jannetai.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JanNet Sahayak: one message (V38). {@code messageText} is always the redacted
 * text (PiiRedactor); {@code intent}, {@code answerSource} and {@code toolsUsed}
 * record how an assistant reply was produced.
 */
@Entity
@Table(name = "assistant_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantMessage {

    public static final String USER = "USER";
    public static final String ASSISTANT = "ASSISTANT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AssistantSession session;

    @Column(name = "sender", nullable = false, length = 10)
    private String sender;

    @Column(name = "message_text", nullable = false, length = 2000)
    private String messageText;

    @Column(name = "intent", length = 40)
    private String intent;

    @Column(name = "answer_source", length = 20)
    private String answerSource;

    @Column(name = "tools_used", length = 255)
    private String toolsUsed;

    @Column(name = "complaint_id")
    private Long complaintId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
