package com.jannetai.backend.client.ai;

import java.util.List;

/**
 * JanNet Sahayak: ai-service's compose reply. {@code answered} is true only for
 * a reply that cites a given source (answer mode) or a recognised civic problem
 * (draft_report mode); otherwise the backend uses its own rule-based answer.
 */
public record AiAssistantComposeResult(
        boolean usedLlm,
        boolean answered,
        String reply,
        List<String> sources,
        String category,
        String description,
        String fallbackReason
) {
}
