package com.jannetai.backend.dto.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/assistant/messages.
 *
 * @param sessionId null for a new conversation, else the id from the previous reply
 * @param text      what the user typed (or the text of a suggested-action chip)
 * @param language  the app language (EN, HI, MR, BN, TA, TE, KN); anything else means EN
 */
public record AssistantMessageRequest(
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "sessionId must be the id from a previous reply")
        String sessionId,
        @NotBlank @Size(max = 500) String text,
        @Size(max = 5) String language
) {
}
