package com.jannetai.backend.dto.assistant;

/** GET /api/v1/assistant/articles - help articles in the requested language (English where missing). */
public record HelpArticleResponse(String slug, String topic, String title, String body) {
}
