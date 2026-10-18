package com.jannetai.backend.client.ai;

import java.util.List;

/**
 * JanNet Sahayak: body of ai-service's {@code POST /api/v1/ai/assistant/compose}
 * (ai-service/app/schemas/assistant.py). Everything in it is already redacted
 * (PiiRedactor) and limited to what the signed-in user may see.
 *
 * @param mode     "answer" (word a reply from facts/articles) or "draft_report" (guided filing)
 * @param language EN, HI, MR, BN, TA, TE or KN
 */
public record AiAssistantComposeRequest(
        String mode,
        String question,
        String language,
        List<Fact> facts,
        List<Article> articles
) {
    public static final String MODE_ANSWER = "answer";
    public static final String MODE_DRAFT_REPORT = "draft_report";

    /** @param source the id a reply must cite, e.g. {@code complaint:JN-2026-000123} */
    public record Fact(String source, String text) {
    }

    public record Article(String slug, String title, String body) {
    }
}
