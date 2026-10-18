package com.jannetai.backend.service.assistant;

import com.jannetai.backend.client.ai.AiAssistantComposeRequest;
import com.jannetai.backend.client.ai.AiServiceCallException;
import com.jannetai.backend.dto.assistant.AssistantAction;
import com.jannetai.backend.dto.assistant.AssistantCard;
import com.jannetai.backend.dto.assistant.AssistantMessageRequest;
import com.jannetai.backend.dto.assistant.AssistantReply;
import com.jannetai.backend.dto.assistant.HelpArticleResponse;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.jannetai.backend.service.assistant.AssistantTestWorld.aiAnswer;
import static com.jannetai.backend.service.assistant.AssistantTestWorld.aiDraft;
import static com.jannetai.backend.service.assistant.AssistantTestWorld.ask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** JanNet Sahayak v1 (AssistantService): answers, scoping, grounding, privacy and fallbacks. */
class AssistantServiceTest {

    private final AssistantTestWorld w = new AssistantTestWorld();
    private final AssistantService service = w.service(true);

    // ---- complaint status ----

    @Test
    void statusOfOwnComplaintMatchesTheAppAndIsLogged() {
        AssistantReply r = ask(service, w.asha, "status of JN-2026-000101", "EN");

        assertThat(r.sessionId()).isEqualTo(AssistantTestWorld.SESSION);
        assertThat(r.intent()).isEqualTo("COMPLAINT_STATUS");
        assertThat(r.source()).isEqualTo(AssistantService.SOURCE_RULE);
        assertThat(r.reply()).isEqualTo("Complaint JN-2026-000101 (pothole) is now: In Progress. "
                + "Work on it has started. It is with Public Works Gaya.");
        assertThat(r.cards()).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(AssistantCard.COMPLAINT);
            assertThat(c.status()).isEqualTo(ComplaintStatus.IN_PROGRESS);
            assertThat(c.complaintId()).isEqualTo(101L);
        });
        assertThat(r.suggestedActions()).extracting(AssistantAction::type, AssistantAction::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(AssistantAction.OPEN_COMPLAINT, "101"));

        ArgumentCaptor<AssistantService.Outcome> logged = ArgumentCaptor.forClass(AssistantService.Outcome.class);
        verify(w.store).record(eq(5L), eq("EN"), eq("status of JN-2026-000101"), logged.capture());
        assertThat(logged.getValue().complaintId()).isEqualTo(101L);
        assertThat(logged.getValue().tools()).containsExactly("complaint_lookup");
        verify(w.complaintService).getDetail(w.asha, 101L); // scoped as the signed-in user
        verify(w.aiServiceClient, never()).composeAssistant(any());
    }

    @Test
    void anotherUsersComplaintIsAnsweredExactlyLikeAMissingOne() {
        AssistantReply other = ask(service, w.asha, "JN-2026-000900", "EN");
        AssistantReply missing = ask(service, w.asha, "JN-2026-999999", "EN");

        assertThat(other.reply().replace("JN-2026-000900", "#")).isEqualTo(missing.reply().replace("JN-2026-999999", "#"));
        assertThat(other.cards()).isEmpty();
        assertThat(AssistantTestWorld.allText(other)).doesNotContain(AssistantTestWorld.OTHER_DEPARTMENT)
                .doesNotContain("Assigned").doesNotContain("water leakage");
        verify(w.complaintService).getDetail(w.asha, 900L);
    }

    @Test
    void statusInHindiUsesTheAppsHindiLabel() {
        AssistantReply r = ask(service, w.asha, "JN-2026-000101 कहाँ है", "HI");
        assertThat(r.reply()).startsWith("शिकायत JN-2026-000101 (गड्ढा) की स्थिति: प्रगति में।")
                .contains("Public Works Gaya");
    }

    @Test
    void resolvedComplaintTellsWhenItClosesAndIsEscalated() {
        AssistantReply r = ask(service, w.asha, "jn-2026-103", "EN");
        assertThat(r.reply()).contains("is now: Resolved.").contains("press Confirm")
                .contains("It has been escalated to a higher level.").contains("21-10-2026");
    }

    @Test
    void rejectionGivesTheReasonAndTheAppealChip() {
        AssistantReply r = ask(service, w.asha, "why was my complaint rejected?", "EN");
        assertThat(r.intent()).isEqualTo("REJECTION_REASON");
        assertThat(r.reply()).contains("JN-2026-000102").contains("Rejected")
                .contains("the photo does not show the problem clearly enough").contains("appeal once");
        assertThat(r.reply()).doesNotContain("It is with"); // no department line for a rejected complaint
        assertThat(r.suggestedActions()).extracting(AssistantAction::label).contains("How do I appeal a rejection?");
    }

    @Test
    void noRejectedComplaintExplainsRejectionsFromTheArticle() {
        AssistantReply r = ask(service, w.newCitizen, "why was my complaint rejected?", "EN");
        assertThat(r.reply()).startsWith("None of your complaints has been rejected.").contains("A rejection always has a reason");
        assertThat(r.cards()).extracting(AssistantCard::slug).containsExactly("rejected-why");
    }

    // ---- my complaints ----

    @Test
    void myComplaintsListsTheLatestAsCards() {
        AssistantReply r = ask(service, w.asha, "Where is my complaint?", "EN");
        assertThat(r.intent()).isEqualTo("MY_COMPLAINTS");
        assertThat(r.reply()).isEqualTo("Complaints found: 3. Here are the latest. Tap a card to open it.");
        assertThat(r.cards()).extracting(AssistantCard::referenceNumber)
                .containsExactly("JN-2026-000101", "JN-2026-000102", "JN-2026-000103");
        assertThat(r.suggestedActions()).extracting(AssistantAction::type).containsExactly(AssistantAction.OPEN_MY_COMPLAINTS);
    }

    @Test
    void myPotholeQuestionGivesThatComplaintsFullStatus() {
        AssistantReply r = ask(service, w.asha, "is my pothole fixed yet?", "EN");
        assertThat(r.intent()).isEqualTo("MY_COMPLAINTS");
        assertThat(r.reply()).startsWith("Complaint JN-2026-000101 (pothole) is now: In Progress.");
    }

    @Test
    void citizenWithoutComplaintsIsOfferedToReport() {
        AssistantReply r = ask(service, w.newCitizen, "meri shikayat kahan hai", "HI");
        assertThat(r.reply()).isEqualTo(AssistantTexts.get(AssistantTexts.NO_COMPLAINTS, "HI"));
        assertThat(r.suggestedActions()).extracting(AssistantAction::type).contains(AssistantAction.START_REPORT);
    }

    // ---- guided filing ----

    @Test
    void draftUsesTheAiDescriptionButSendsNoPersonalData() {
        when(w.aiServiceClient.composeAssistant(any())).thenReturn(aiDraft("POTHOLE", "Large pothole near the school gate."));

        AssistantReply r = ask(service, w.asha, "big pothole near school gate, call me 9876543210", "EN");

        assertThat(r.intent()).isEqualTo("REPORT_DRAFT");
        assertThat(r.source()).isEqualTo(AssistantService.SOURCE_AI);
        assertThat(r.cards()).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(AssistantCard.REPORT_DRAFT);
            assertThat(c.category()).isEqualTo(ComplaintCategory.POTHOLE);
            assertThat(c.description()).isEqualTo("Large pothole near the school gate.");
        });
        assertThat(r.suggestedActions()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(AssistantAction.START_REPORT);
            assertThat(a.value()).isEqualTo("Large pothole near the school gate.");
        });
        ArgumentCaptor<AiAssistantComposeRequest> sent = ArgumentCaptor.forClass(AiAssistantComposeRequest.class);
        verify(w.aiServiceClient).composeAssistant(sent.capture());
        assertThat(sent.getValue().mode()).isEqualTo(AiAssistantComposeRequest.MODE_DRAFT_REPORT);
        assertThat(sent.getValue().question()).doesNotContain("9876543210").contains(PiiRedactor.REMOVED);
    }

    @Test
    void draftStillWorksWhenTheAiServiceIsDown() {
        when(w.aiServiceClient.composeAssistant(any())).thenThrow(new AiServiceCallException("AI_SERVICE_UNREACHABLE", "down", null));

        AssistantReply r = ask(service, w.asha, "kooda pada hai market ke paas", "HI");

        assertThat(r.source()).isEqualTo(AssistantService.SOURCE_RULE);
        assertThat(r.cards().get(0).category()).isEqualTo(ComplaintCategory.GARBAGE_OVERFLOW);
        assertThat(r.cards().get(0).description()).isEqualTo("Kooda pada hai market ke paas");
        assertThat(r.reply()).isEqualTo(AssistantTexts.get(AssistantTexts.DRAFT_READY, "HI"));
    }

    @Test
    void staffCannotFileAndAiIsNotCalled() {
        AssistantReply r = ask(service, w.officer, "there is a pothole near the bus stand", "EN");
        assertThat(r.reply()).isEqualTo("Only citizens can report a problem in this app.");
        verify(w.aiServiceClient, never()).composeAssistant(any());
    }

    // ---- help articles and open questions ----

    @Test
    void faqIsAnsweredFromTheArticleInTheUsersLanguage() {
        AssistantReply r = ask(service, w.asha, "भाषा कैसे बदलें", "HI");
        assertThat(r.source()).isEqualTo(AssistantService.SOURCE_ARTICLE);
        assertThat(r.cards()).singleElement().satisfies(c -> {
            assertThat(c.slug()).isEqualTo("language");
            assertThat(c.title()).isEqualTo("भाषा बदलना");
        });
        assertThat(r.reply()).startsWith("सेटिंग्स खोलें");
        verify(w.aiServiceClient, never()).composeAssistant(any());
    }

    @Test
    void openQuestionIsWordedByAiOnlyFromACitedArticle() {
        when(w.aiServiceClient.composeAssistant(any())).thenReturn(
                aiAnswer("Your personal details are not shown in public lists.", "privacy"));

        AssistantReply r = ask(service, w.asha, "will anybody see my personal details?", "EN");

        assertThat(r.source()).isEqualTo(AssistantService.SOURCE_AI);
        assertThat(r.reply()).isEqualTo("Your personal details are not shown in public lists.");
        assertThat(r.cards()).extracting(AssistantCard::slug).containsExactly("privacy");
        ArgumentCaptor<AiAssistantComposeRequest> sent = ArgumentCaptor.forClass(AiAssistantComposeRequest.class);
        verify(w.aiServiceClient).composeAssistant(sent.capture());
        assertThat(sent.getValue().mode()).isEqualTo(AiAssistantComposeRequest.MODE_ANSWER);
        assertThat(sent.getValue().articles()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(sent.getValue().facts()).isEmpty();
    }

    @Test
    void anAiAnswerCitingNothingWeGaveIsDiscarded() {
        when(w.aiServiceClient.composeAssistant(any())).thenReturn(aiAnswer("Officers will call you tomorrow.", "made-up"));

        AssistantReply r = ask(service, w.asha, "will anybody see my personal details?", "EN");

        assertThat(r.source()).isEqualTo(AssistantService.SOURCE_FALLBACK);
        assertThat(r.reply()).doesNotContain("tomorrow");
    }

    @Test
    void unrelatedQuestionNeverSpendsTheFreeQuota() {
        AssistantReply r = ask(service, w.asha, "who will win the cricket world cup", "EN");
        assertThat(r.source()).isEqualTo(AssistantService.SOURCE_FALLBACK);
        assertThat(r.reply()).startsWith("Sorry, I can only help");
        verify(w.aiServiceClient, never()).composeAssistant(any());
    }

    @Test
    void withTheLlmSwitchedOffNothingIsSentToAi() {
        AssistantService rulesOnly = w.service(false);
        ask(rulesOnly, w.asha, "will anybody see my personal details?", "EN");
        ask(rulesOnly, w.asha, "there is a pothole near the bus stand", "EN");
        verify(w.aiServiceClient, never()).composeAssistant(any());
    }

    // ---- privacy, safety, limits ----

    @Test
    void personalDataIsRemovedBeforeTheMessageIsStored() {
        ask(service, w.asha, "I am Asha Rao, my number is 9876543210, mail asha@example.com - where is my complaint", "EN");
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(w.store).record(eq(5L), eq("EN"), stored.capture(), any());
        assertThat(stored.getValue()).doesNotContain("9876543210").doesNotContain("asha@example.com")
                .doesNotContain("Asha").doesNotContain("Rao");
    }

    @Test
    void emergencyPointsTo112() {
        AssistantReply r = ask(service, w.asha, "there is fire in the building", "EN");
        assertThat(r.intent()).isEqualTo("EMERGENCY");
        assertThat(r.reply()).contains("112");
    }

    @Test
    void menuChipsFollowTheLanguageAndRole() {
        AssistantReply citizen = ask(service, w.asha, "வணக்கம்", "TA");
        assertThat(citizen.reply()).isEqualTo(AssistantTexts.get(AssistantTexts.GREETING, "TA"));
        assertThat(citizen.suggestedActions()).hasSize(4).allSatisfy(a -> assertThat(a.type()).isEqualTo(AssistantAction.ASK))
                .extracting(AssistantAction::label).contains(AssistantTexts.get(AssistantTexts.CHIP_WHERE, "TA"));
        AssistantReply officer = ask(service, w.officer, "hello", "EN");
        assertThat(officer.suggestedActions()).hasSize(2);
    }

    @Test
    void tooManyMessagesAreRefusedBeforeAnythingIsStored() {
        AssistantService limited = w.service(true, 1);
        ask(limited, w.asha, "hi", "EN");
        assertThatThrownBy(() -> ask(limited, w.asha, "hi again", "EN"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(w.store, times(1)).record(any(), any(), any(), any());
    }

    @Test
    void anUnexpectedErrorBecomesTheMenuNotAnErrorPage() {
        when(w.complaintService.list(any(), any(), any(), any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("db down"));
        AssistantReply r = ask(service, w.asha, "where is my complaint", "EN");
        assertThat(r.source()).isEqualTo(AssistantService.SOURCE_FALLBACK);
        verify(w.store).record(eq(5L), eq("EN"), anyString(), any());
    }

    @Test
    void existingSessionIdIsPassedToTheStore() {
        service.handle(w.asha, new AssistantMessageRequest(AssistantTestWorld.SESSION, "hi", "hi"));
        verify(w.store).openSession(w.asha, AssistantTestWorld.SESSION, "HI");
    }

    // ---- articles list ----

    @Test
    void articlesAreListedInTheLanguageWithEnglishFallback() {
        List<HelpArticleResponse> hi = service.articles("hi");
        assertThat(hi).hasSize(20);
        assertThat(hi.get(0).slug()).isEqualTo("how-to-report");
        assertThat(hi.get(0).title()).isEqualTo("समस्या की शिकायत कैसे करें?");
        List<HelpArticleResponse> unknown = service.articles("fr");
        assertThat(unknown).hasSize(20);
        assertThat(unknown.get(0).title()).isEqualTo("How do I report a problem?");
    }

    @Test
    void tidyCapitalisesCollapsesAndCaps() {
        assertThat(AssistantService.tidy("  big   pothole  ")).isEqualTo("Big pothole");
        assertThat(AssistantService.tidy("x".repeat(600))).hasSize(500);
    }
}
