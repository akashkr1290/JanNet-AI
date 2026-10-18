package com.jannetai.backend.dto.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;

import java.time.LocalDateTime;

/**
 * Structured content under an assistant reply. The app draws a COMPLAINT card
 * with the same status label as its complaint screens, so the assistant's
 * status always matches the app.
 *
 * <ul>
 *   <li>{@code COMPLAINT}: complaintId, referenceNumber, category, status, departmentName, isEscalated, updatedAt</li>
 *   <li>{@code ARTICLE}: slug, title, body (the help article the answer came from)</li>
 *   <li>{@code REPORT_DRAFT}: category, description (guided filing)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantCard(
        String type,
        Long complaintId,
        String referenceNumber,
        ComplaintCategory category,
        ComplaintStatus status,
        String departmentName,
        Boolean isEscalated,
        LocalDateTime updatedAt,
        String slug,
        String title,
        String body,
        String description
) {
    public static final String COMPLAINT = "COMPLAINT";
    public static final String ARTICLE = "ARTICLE";
    public static final String REPORT_DRAFT = "REPORT_DRAFT";

    public static AssistantCard complaint(Long id, String reference, ComplaintCategory category, ComplaintStatus status,
                                          String departmentName, Boolean escalated, LocalDateTime updatedAt) {
        return new AssistantCard(COMPLAINT, id, reference, category, status, departmentName, escalated, updatedAt,
                null, null, null, null);
    }

    public static AssistantCard article(String slug, String title, String body) {
        return new AssistantCard(ARTICLE, null, null, null, null, null, null, null, slug, title, body, null);
    }

    public static AssistantCard reportDraft(ComplaintCategory category, String description) {
        return new AssistantCard(REPORT_DRAFT, null, null, category, null, null, null, null, null, null, null, description);
    }
}
