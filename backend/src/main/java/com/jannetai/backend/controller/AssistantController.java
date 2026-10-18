package com.jannetai.backend.controller;

import com.jannetai.backend.dto.assistant.AssistantMessageRequest;
import com.jannetai.backend.dto.assistant.AssistantReply;
import com.jannetai.backend.dto.assistant.HelpArticleResponse;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.assistant.AssistantService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * JanNet Sahayak v1 (in-app assistant). Answers are read-only and use the
 * signed-in user's own scope - see AssistantService.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
@Tag(name = "Assistant", description = "JanNet Sahayak: complaint status, help articles and guided filing")
public class AssistantController {

    private final AssistantService assistantService;

    @PostMapping("/messages")
    @PreAuthorize("isAuthenticated()")
    public AssistantReply message(@AuthenticationPrincipal UserPrincipal principal,
                                  @Valid @RequestBody AssistantMessageRequest request) {
        return assistantService.handle(principal.getUser(), request);
    }

    @GetMapping("/articles")
    @PreAuthorize("isAuthenticated()")
    public List<HelpArticleResponse> articles(@RequestParam(defaultValue = "EN") String language) {
        return assistantService.articles(language);
    }
}
