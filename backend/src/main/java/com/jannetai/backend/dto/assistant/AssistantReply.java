package com.jannetai.backend.dto.assistant;

import java.util.List;

/**
 * Reply of POST /api/v1/assistant/messages.
 *
 * @param source RULE (from the user's own data), ARTICLE (a help article), AI (worded by
 *               Gemini from those sources) or FALLBACK (could not answer; menu offered)
 * @param intent what the assistant understood, e.g. COMPLAINT_STATUS (for the app and the log)
 */
public record AssistantReply(
        String sessionId,
        String reply,
        String source,
        String intent,
        List<AssistantCard> cards,
        List<AssistantAction> suggestedActions
) {
}
