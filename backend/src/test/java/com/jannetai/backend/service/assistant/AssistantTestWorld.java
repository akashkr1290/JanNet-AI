package com.jannetai.backend.service.assistant;

import com.jannetai.backend.client.ai.AiAssistantComposeRequest;
import com.jannetai.backend.client.ai.AiAssistantComposeResult;
import com.jannetai.backend.client.ai.AiServiceClient;
import com.jannetai.backend.dto.assistant.AssistantMessageRequest;
import com.jannetai.backend.dto.assistant.AssistantReply;
import com.jannetai.backend.dto.complaint.ComplaintResponse;
import com.jannetai.backend.dto.complaint.ComplaintSummaryResponse;
import com.jannetai.backend.entity.AssistantSession;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.HelpArticleRepository;
import com.jannetai.backend.service.complaint.ComplaintService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * A small fixed world for the assistant tests: citizen Asha (id 42) with three
 * complaints, another citizen's complaint JN-2026-000900 that Asha must never
 * see, and the real seeded help articles (HelpArticleFixtures).
 */
class AssistantTestWorld {

    static final String SESSION = "11111111-2222-3333-4444-555555555555";
    static final String OTHER_DEPARTMENT = "Water Works Patna";

    final User asha = User.builder().userId(42L).role(Role.CITIZEN).fullName("Asha Rao").build();
    final User newCitizen = User.builder().userId(43L).role(Role.CITIZEN).fullName("Ravi Verma").build();
    final User officer = User.builder().userId(7L).role(Role.GOVERNMENT_OFFICER).fullName("Officer Singh").build();

    final ComplaintService complaintService = mock(ComplaintService.class);
    final ComplaintRepository complaintRepository = mock(ComplaintRepository.class);
    final HelpArticleRepository helpArticleRepository = mock(HelpArticleRepository.class);
    final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
    final AssistantConversationStore store = mock(AssistantConversationStore.class);

    final ComplaintResponse inProgress = detail(101L, "JN-2026-000101", ComplaintCategory.POTHOLE,
            ComplaintStatus.IN_PROGRESS, "Public Works Gaya", false, null, null);
    final ComplaintResponse rejected = detail(102L, "JN-2026-000102", ComplaintCategory.GARBAGE_OVERFLOW,
            ComplaintStatus.REJECTED, null, false, "INSUFFICIENT_EVIDENCE", null);
    final ComplaintResponse resolved = detail(103L, "JN-2026-000103", ComplaintCategory.BROKEN_STREET_LIGHT,
            ComplaintStatus.RESOLVED, "Electricity Gaya", true, null, LocalDateTime.of(2026, 10, 21, 9, 0));
    final ComplaintResponse othersComplaint = detail(900L, "JN-2026-000900", ComplaintCategory.WATER_LEAKAGE,
            ComplaintStatus.ASSIGNED, OTHER_DEPARTMENT, false, null, null);

    AssistantTestWorld() {
        lenient().when(store.openSession(any(), any(), anyString())).thenReturn(
                AssistantSession.builder().sessionId(5L).publicId(SESSION).messageCount(0).build());
        lenient().when(helpArticleRepository.findByLanguageInAndIsActiveTrueOrderBySortOrderAsc(any()))
                .thenAnswer(inv -> HelpArticleFixtures.inLanguages((Collection<String>) inv.getArgument(0)));

        lenient().when(complaintRepository.findByReferenceNumber(anyString())).thenReturn(Optional.empty());
        lenient().when(complaintService.getDetail(any(), anyLong()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "This complaint is not in your scope"));
        for (ComplaintResponse c : List.of(inProgress, rejected, resolved, othersComplaint)) {
            lenient().when(complaintRepository.findByReferenceNumber(c.referenceNumber()))
                    .thenReturn(Optional.of(Complaint.builder().complaintId(c.complaintId()).referenceNumber(c.referenceNumber()).build()));
        }
        for (ComplaintResponse c : List.of(inProgress, rejected, resolved)) {
            lenient().doReturn(c).when(complaintService).getDetail(eq(asha), eq(c.complaintId()));
        }
        // the other citizen's complaint: ComplaintService refuses Asha (403) - stubbed above for every id

        lenient().when(complaintService.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());
        lenient().when(complaintService.list(asha, null, null, null, 0, 5))
                .thenReturn(page(inProgress, rejected, resolved));
        lenient().when(complaintService.list(asha, null, ComplaintCategory.POTHOLE, null, 0, 5)).thenReturn(page(inProgress));
        lenient().when(complaintService.list(asha, ComplaintStatus.REJECTED, null, null, 0, 1)).thenReturn(page(rejected));
    }

    AssistantService service(boolean llmEnabled) {
        return service(llmEnabled, 1000);
    }

    AssistantService service(boolean llmEnabled, int messagesPerMinute) {
        return new AssistantService(complaintService, complaintRepository, helpArticleRepository, aiServiceClient, store,
                new AssistantRateLimiter(messagesPerMinute), llmEnabled);
    }

    static AssistantReply ask(AssistantService service, User user, String text, String language) {
        return service.handle(user, new AssistantMessageRequest(null, text, language));
    }

    static AiAssistantComposeResult aiAnswer(String reply, String... sources) {
        return new AiAssistantComposeResult(true, true, reply, List.of(sources), null, null, null);
    }

    static AiAssistantComposeResult aiDraft(String category, String description) {
        return new AiAssistantComposeResult(true, true, null, List.of(), category, description, null);
    }

    static AiAssistantComposeResult aiNoAnswer(String reason) {
        return new AiAssistantComposeResult(true, false, null, List.of(), null, null, reason);
    }

    static String allText(AssistantReply r) {
        List<String> parts = new ArrayList<>();
        parts.add(r.reply());
        r.cards().forEach(c -> parts.add(String.valueOf(c)));
        r.suggestedActions().forEach(a -> parts.add(String.valueOf(a)));
        return String.join(" | ", parts);
    }

    static Map<String, String> modeOf(AiAssistantComposeRequest r) {
        return Map.of("mode", r.mode(), "language", r.language());
    }

    private static Page<ComplaintSummaryResponse> page(ComplaintResponse... items) {
        return new PageImpl<>(java.util.Arrays.stream(items)
                .map(c -> new ComplaintSummaryResponse(c.complaintId(), c.referenceNumber(), c.category(), c.description(),
                        c.status(), null, 1, c.isEscalated(), false, c.createdAt(), c.updatedAt(), null))
                .toList());
    }

    private static ComplaintResponse detail(Long id, String ref, ComplaintCategory category, ComplaintStatus status,
                                            String department, boolean escalated, String reason, LocalDateTime autoClose) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 10, 10, 0);
        return new ComplaintResponse(id, ref, 42L, category, "description", null, department == null ? null : 3L, null,
                status, null, null, 1, escalated, null, false, null, reason, List.of(), List.of(), List.of(), null, null,
                t, t, department, department == null ? null : "Officer Name", autoClose);
    }
}
