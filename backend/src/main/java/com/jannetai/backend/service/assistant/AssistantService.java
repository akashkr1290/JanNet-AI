package com.jannetai.backend.service.assistant;

import com.jannetai.backend.client.ai.AiAssistantComposeRequest;
import com.jannetai.backend.client.ai.AiAssistantComposeResult;
import com.jannetai.backend.client.ai.AiServiceClient;
import com.jannetai.backend.dto.assistant.AssistantAction;
import com.jannetai.backend.dto.assistant.AssistantCard;
import com.jannetai.backend.dto.assistant.AssistantMessageRequest;
import com.jannetai.backend.dto.assistant.AssistantReply;
import com.jannetai.backend.dto.assistant.HelpArticleResponse;
import com.jannetai.backend.dto.complaint.ComplaintResponse;
import com.jannetai.backend.dto.complaint.ComplaintSummaryResponse;
import com.jannetai.backend.entity.AssistantSession;
import com.jannetai.backend.entity.HelpArticle;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.HelpArticleRepository;
import com.jannetai.backend.service.assistant.IntentRouter.Intent;
import com.jannetai.backend.service.assistant.IntentRouter.Routed;
import com.jannetai.backend.service.complaint.ComplaintService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JanNet Sahayak v1 - POST /api/v1/assistant/messages (feature plan
 * plans/2026-10-15_PROJECT_DESCRIPTION_AND_FEATURE_PLAN.md, Feature A).
 *
 * <ol>
 *   <li>Personal data is removed from the text first (PiiRedactor); only the
 *       redacted text is stored or sent anywhere.</li>
 *   <li>IntentRouter recognises the question by fixed rules. Status, my
 *       complaints and rejection answers come from ComplaintService AS THE
 *       SIGNED-IN USER - the same role scoping as the app's screens - so the
 *       assistant can never show more than the user can see in the app. A
 *       complaint the user may not see is answered exactly like one that does
 *       not exist.</li>
 *   <li>FAQ questions are answered from help_articles (HelpArticleMatcher).</li>
 *   <li>Only open questions and guided filing go to ai-service / Gemini (free
 *       tier), with the matching articles as the only allowed sources; an
 *       answer without a citation is discarded. Any ai-service problem falls
 *       back to the rule-based menu.</li>
 * </ol>
 * v1 has no write actions: guided filing only fills the app's form; the
 * citizen still takes the photo, confirms the place and presses Submit.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int MAX_DESCRIPTION = 500;
    private static final int LIST_SIZE = 5;
    /** Statuses for which the department name adds nothing (not yet routed, or not handled). */
    private static final Set<ComplaintStatus> NO_DEPARTMENT_LINE = EnumSet.of(ComplaintStatus.DRAFT,
            ComplaintStatus.SUBMITTED, ComplaintStatus.AI_PROCESSING, ComplaintStatus.REJECTED, ComplaintStatus.DUPLICATE);

    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_ARTICLE = "ARTICLE";
    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_FALLBACK = "FALLBACK";

    /** How one reply was produced - returned to the app and written to assistant_messages. */
    public record Outcome(Intent intent, String reply, String source, List<AssistantCard> cards,
                          List<AssistantAction> actions, List<String> tools, Long complaintId) {
    }

    private final ComplaintService complaintService;
    private final ComplaintRepository complaintRepository;
    private final HelpArticleRepository helpArticleRepository;
    private final AiServiceClient aiServiceClient;
    private final AssistantConversationStore store;
    private final AssistantRateLimiter rateLimiter;
    private final boolean llmEnabled;

    public AssistantService(ComplaintService complaintService, ComplaintRepository complaintRepository,
                            HelpArticleRepository helpArticleRepository, AiServiceClient aiServiceClient,
                            AssistantConversationStore store, AssistantRateLimiter rateLimiter,
                            @Value("${app.assistant.llm-enabled:true}") boolean llmEnabled) {
        this.complaintService = complaintService;
        this.complaintRepository = complaintRepository;
        this.helpArticleRepository = helpArticleRepository;
        this.aiServiceClient = aiServiceClient;
        this.store = store;
        this.rateLimiter = rateLimiter;
        this.llmEnabled = llmEnabled;
    }

    public AssistantReply handle(User user, AssistantMessageRequest request) {
        rateLimiter.check(user.getUserId());
        String language = AssistantTexts.language(request.language());
        String redacted = PiiRedactor.redact(request.text().strip(), user.getFullName());
        AssistantSession session = store.openSession(user, request.sessionId(), language);
        Outcome outcome;
        try {
            outcome = answer(user, redacted, language);
        } catch (RuntimeException e) {
            // Never show an error page for a chat message: log it and offer the menu.
            log.warn("Assistant could not answer ({}); falling back to the menu", e.getClass().getSimpleName(), e);
            outcome = fallback(Intent.OPEN_QUESTION, user, language);
        }
        store.record(session.getSessionId(), language, redacted, outcome);
        return new AssistantReply(session.getPublicId(), outcome.reply(), outcome.source(), outcome.intent().name(),
                outcome.cards(), outcome.actions());
    }

    /** GET /api/v1/assistant/articles - every active article in the language (English where a translation is missing). */
    public List<HelpArticleResponse> articles(String language) {
        String lang = AssistantTexts.language(language);
        Map<String, HelpArticle> bySlug = preferLanguage(loadArticles(lang), lang);
        return bySlug.values().stream()
                .map(a -> new HelpArticleResponse(a.getSlug(), a.getTopic(), a.getTitle(), a.getBody()))
                .toList();
    }

    // ---- routing ----

    Outcome answer(User user, String text, String language) {
        Routed routed = IntentRouter.route(text);
        return switch (routed.intent()) {
            case GREETING -> rule(Intent.GREETING, AssistantTexts.get(AssistantTexts.GREETING, language),
                    menu(user, language));
            case THANKS -> rule(Intent.THANKS, AssistantTexts.get(AssistantTexts.THANKS, language), menu(user, language));
            case EMERGENCY -> rule(Intent.EMERGENCY, AssistantTexts.get(AssistantTexts.EMERGENCY, language), List.of());
            case COMPLAINT_STATUS -> status(user, routed.referenceNumber(), language);
            case MY_COMPLAINTS -> myComplaints(user, routed.category(), language);
            case REJECTION_REASON -> rejected(user, language);
            case REPORT_DRAFT -> draft(user, text, routed.category(), language);
            case REPORT_START -> reportStart(user, language);
            case HELP_ARTICLE, OPEN_QUESTION -> helpOrOpen(user, text, routed.articleSlug(), language);
        };
    }

    // ---- complaint answers (always as the signed-in user) ----

    private Outcome status(User user, String reference, String language) {
        Optional<ComplaintResponse> detail = visibleComplaint(user, reference);
        if (detail.isEmpty()) {
            return new Outcome(Intent.COMPLAINT_STATUS, AssistantTexts.get(AssistantTexts.NOT_FOUND, language, reference),
                    SOURCE_RULE, List.of(), List.of(AssistantAction.ask(AssistantTexts.get(AssistantTexts.CHIP_WHERE, language))),
                    List.of("complaint_lookup"), null);
        }
        ComplaintResponse d = detail.get();
        return new Outcome(Intent.COMPLAINT_STATUS, statusText(d, language), SOURCE_RULE,
                List.of(card(d)), complaintActions(d, language), List.of("complaint_lookup"), d.complaintId());
    }

    /** The complaint only if this user may open it in the app; otherwise empty (same as not existing). */
    private Optional<ComplaintResponse> visibleComplaint(User user, String reference) {
        var complaint = complaintRepository.findByReferenceNumber(reference);
        if (complaint.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(complaintService.getDetail(user, complaint.get().getComplaintId()));
        } catch (ResponseStatusException outOfScope) {
            return Optional.empty();
        }
    }

    String statusText(ComplaintResponse d, String language) {
        StringBuilder sb = new StringBuilder(AssistantTexts.get(AssistantTexts.STATUS_LINE, language,
                d.referenceNumber(), AssistantTexts.category(d.category(), language),
                AssistantTexts.statusLabel(d.status(), language)));
        String reason = d.status() == ComplaintStatus.REJECTED
                ? AssistantTexts.rejectionReason(d.rejectionReasonCode(), language) : null;
        sb.append(' ').append(AssistantTexts.statusExplanation(d.status(), language, reason));
        if (d.departmentName() != null && !d.departmentName().isBlank() && !NO_DEPARTMENT_LINE.contains(d.status())) {
            sb.append(' ').append(AssistantTexts.get(AssistantTexts.WITH_DEPARTMENT, language, d.departmentName()));
        }
        if (Boolean.TRUE.equals(d.isEscalated())) {
            sb.append(' ').append(AssistantTexts.get(AssistantTexts.ESCALATED_NOTE, language));
        }
        if (d.status() == ComplaintStatus.RESOLVED && d.autoCloseAt() != null) {
            sb.append(' ').append(AssistantTexts.get(AssistantTexts.AUTO_CLOSE, language, DATE.format(d.autoCloseAt())));
        }
        return sb.toString();
    }

    private Outcome myComplaints(User user, ComplaintCategory category, String language) {
        Page<ComplaintSummaryResponse> page = complaintService.list(user, null, category, null, 0, LIST_SIZE);
        if (category != null && page.getTotalElements() == 0) {
            page = complaintService.list(user, null, null, null, 0, LIST_SIZE);
        }
        if (page.getTotalElements() == 0) {
            List<AssistantAction> actions = new ArrayList<>();
            if (user.getRole() == Role.CITIZEN) {
                actions.add(new AssistantAction(AssistantAction.START_REPORT,
                        AssistantTexts.get(AssistantTexts.ACTION_START_REPORT, language), ""));
            }
            actions.add(AssistantAction.ask(AssistantTexts.get(AssistantTexts.CHIP_REPORT, language)));
            return new Outcome(Intent.MY_COMPLAINTS, AssistantTexts.get(AssistantTexts.NO_COMPLAINTS, language),
                    SOURCE_RULE, List.of(), actions, List.of("complaint_list"), null);
        }
        if (page.getTotalElements() == 1) {
            // One complaint: give its full status straight away.
            Outcome one = status(user, page.getContent().get(0).referenceNumber(), language);
            return new Outcome(Intent.MY_COMPLAINTS, one.reply(), one.source(), one.cards(), one.actions(),
                    List.of("complaint_list", "complaint_lookup"), one.complaintId());
        }
        List<AssistantCard> cards = page.getContent().stream()
                .map(s -> AssistantCard.complaint(s.complaintId(), s.referenceNumber(), s.category(), s.status(), null,
                        s.isEscalated(), s.updatedAt()))
                .toList();
        return new Outcome(Intent.MY_COMPLAINTS,
                AssistantTexts.get(AssistantTexts.MY_COMPLAINTS, language, page.getTotalElements()), SOURCE_RULE, cards,
                List.of(new AssistantAction(AssistantAction.OPEN_MY_COMPLAINTS,
                        AssistantTexts.get(AssistantTexts.ACTION_MY_COMPLAINTS, language), null)),
                List.of("complaint_list"), null);
    }

    private Outcome rejected(User user, String language) {
        Page<ComplaintSummaryResponse> page = complaintService.list(user, ComplaintStatus.REJECTED, null, null, 0, 1);
        if (page.getTotalElements() == 0) {
            Outcome article = articleOutcome(Intent.REJECTION_REASON, "rejected-why", user, language);
            String reply = AssistantTexts.get(AssistantTexts.NO_REJECTED, language)
                    + (article == null ? "" : "\n\n" + article.reply());
            return new Outcome(Intent.REJECTION_REASON, reply, SOURCE_RULE,
                    article == null ? List.of() : article.cards(), menu(user, language),
                    List.of("complaint_list", "help_article"), null);
        }
        Outcome one = status(user, page.getContent().get(0).referenceNumber(), language);
        List<AssistantAction> actions = new ArrayList<>(one.actions());
        actions.add(AssistantAction.ask(AssistantTexts.get(AssistantTexts.CHIP_APPEAL, language)));
        return new Outcome(Intent.REJECTION_REASON, one.reply(), SOURCE_RULE, one.cards(), actions,
                List.of("complaint_list", "complaint_lookup"), one.complaintId());
    }

    // ---- guided filing (fills the form only) ----

    private Outcome draft(User user, String text, ComplaintCategory category, String language) {
        if (user.getRole() != Role.CITIZEN) {
            return rule(Intent.REPORT_DRAFT, AssistantTexts.get(AssistantTexts.DRAFT_STAFF, language), menu(user, language));
        }
        String description = tidy(text);
        ComplaintCategory finalCategory = category == null ? ComplaintCategory.GENERAL : category;
        String source = SOURCE_RULE;
        List<String> tools = new ArrayList<>();
        if (llmEnabled) {
            tools.add("ai_draft");
            AiAssistantComposeResult r = compose(new AiAssistantComposeRequest(
                    AiAssistantComposeRequest.MODE_DRAFT_REPORT, text, language, List.of(), List.of()));
            if (r != null && r.answered() && r.description() != null && !r.description().isBlank()) {
                description = tidy(r.description());
                finalCategory = parseCategory(r.category(), finalCategory);
                source = SOURCE_AI;
            }
        }
        return new Outcome(Intent.REPORT_DRAFT, AssistantTexts.get(AssistantTexts.DRAFT_READY, language), source,
                List.of(AssistantCard.reportDraft(finalCategory, description)),
                List.of(new AssistantAction(AssistantAction.START_REPORT,
                        AssistantTexts.get(AssistantTexts.ACTION_START_REPORT, language), description)),
                tools, null);
    }

    private Outcome reportStart(User user, String language) {
        if (user.getRole() != Role.CITIZEN) {
            return rule(Intent.REPORT_START, AssistantTexts.get(AssistantTexts.DRAFT_STAFF, language), menu(user, language));
        }
        Outcome howTo = articleOutcome(Intent.REPORT_START, "how-to-report", user, language);
        return new Outcome(Intent.REPORT_START, AssistantTexts.get(AssistantTexts.REPORT_START, language), SOURCE_RULE,
                howTo == null ? List.of() : howTo.cards(),
                List.of(new AssistantAction(AssistantAction.START_REPORT,
                        AssistantTexts.get(AssistantTexts.ACTION_START_REPORT, language), "")),
                List.of("help_article"), null);
    }

    static String tidy(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        if (!t.isEmpty() && Character.isLowerCase(t.charAt(0))) {
            t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        return t.length() <= MAX_DESCRIPTION ? t : t.substring(0, MAX_DESCRIPTION).strip();
    }

    private static ComplaintCategory parseCategory(String value, ComplaintCategory fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return ComplaintCategory.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ---- help articles and open questions ----

    private Outcome helpOrOpen(User user, String text, String slugHint, String language) {
        List<HelpArticle> rows = loadArticles(language);
        String slug = slugHint;
        List<HelpArticleMatcher.Scored> ranked = HelpArticleMatcher.rank(text, entries(rows));
        if (slug == null && !ranked.isEmpty() && ranked.get(0).keywordScore() >= 1) {
            slug = ranked.get(0).slug();
        }
        if (slug != null) {
            Outcome article = articleOutcome(Intent.HELP_ARTICLE, slug, rows, user, language);
            if (article != null) {
                return article;
            }
        }
        if (llmEnabled) {
            Outcome ai = openQuestion(user, text, ranked, rows, language);
            if (ai != null) {
                return ai;
            }
        }
        return fallback(Intent.OPEN_QUESTION, user, language);
    }

    /** Gemini words an answer from at most three related articles - and must cite one - or nothing is used. */
    private Outcome openQuestion(User user, String text, List<HelpArticleMatcher.Scored> ranked, List<HelpArticle> rows,
                                 String language) {
        Map<String, HelpArticle> bySlug = preferLanguage(rows, language);
        List<AiAssistantComposeRequest.Article> sources = ranked.stream()
                .filter(s -> s.overlapScore() >= 1)
                .limit(3)
                .map(s -> bySlug.get(s.slug()))
                .filter(a -> a != null)
                .map(a -> new AiAssistantComposeRequest.Article(a.getSlug(), a.getTitle(), a.getBody()))
                .toList();
        if (sources.isEmpty()) {
            return null; // nothing to ground an answer in - do not spend the free quota
        }
        AiAssistantComposeResult r = compose(new AiAssistantComposeRequest(
                AiAssistantComposeRequest.MODE_ANSWER, text, language, List.of(), sources));
        if (r == null || !r.answered() || r.reply() == null || r.reply().isBlank()) {
            return null;
        }
        List<AssistantCard> cards = new ArrayList<>();
        for (String cited : r.sources() == null ? List.<String>of() : r.sources()) {
            HelpArticle a = bySlug.get(cited);
            if (a != null) {
                cards.add(AssistantCard.article(a.getSlug(), a.getTitle(), a.getBody()));
            }
        }
        if (cards.isEmpty()) {
            return null; // the reply must rest on an article we gave it
        }
        return new Outcome(Intent.OPEN_QUESTION, PiiRedactor.redact(r.reply().strip(), null), SOURCE_AI, cards,
                menu(user, language), List.of("help_article", "ai_compose"), null);
    }

    private Outcome articleOutcome(Intent intent, String slug, User user, String language) {
        return articleOutcome(intent, slug, loadArticles(language), user, language);
    }

    private Outcome articleOutcome(Intent intent, String slug, List<HelpArticle> rows, User user, String language) {
        HelpArticle a = preferLanguage(rows, language).get(slug);
        if (a == null) {
            return null;
        }
        return new Outcome(intent, a.getBody(), SOURCE_ARTICLE, List.of(AssistantCard.article(a.getSlug(), a.getTitle(), a.getBody())),
                menu(user, language), List.of("help_article"), null);
    }

    private List<HelpArticle> loadArticles(String language) {
        return helpArticleRepository.findByLanguageInAndIsActiveTrueOrderBySortOrderAsc(
                "EN".equals(language) ? List.of("EN") : List.of(language, "EN"));
    }

    /** One row per slug: the user's language when present, else English; in sort order. */
    private static Map<String, HelpArticle> preferLanguage(List<HelpArticle> rows, String language) {
        Map<String, HelpArticle> out = new LinkedHashMap<>();
        for (HelpArticle a : rows) {
            HelpArticle current = out.get(a.getSlug());
            if (current == null || (!language.equals(current.getLanguage()) && language.equals(a.getLanguage()))) {
                out.put(a.getSlug(), a);
            }
        }
        return out;
    }

    private static List<HelpArticleMatcher.Entry> entries(List<HelpArticle> rows) {
        return rows.stream()
                .map(a -> new HelpArticleMatcher.Entry(a.getSlug(), a.getLanguage(), a.getTitle(), a.getBody(),
                        a.getKeywords() == null ? "" : a.getKeywords(), a.getSortOrder() == null ? 0 : a.getSortOrder()))
                .toList();
    }

    private AiAssistantComposeResult compose(AiAssistantComposeRequest request) {
        try {
            return aiServiceClient.composeAssistant(request);
        } catch (RuntimeException e) {
            log.info("Assistant: ai-service compose unavailable ({}), using the rule-based answer", e.getClass().getSimpleName());
            return null;
        }
    }

    // ---- small helpers ----

    private Outcome fallback(Intent intent, User user, String language) {
        return new Outcome(intent, AssistantTexts.get(AssistantTexts.FALLBACK, language), SOURCE_FALLBACK, List.of(),
                menu(user, language), List.of(), null);
    }

    private static Outcome rule(Intent intent, String reply, List<AssistantAction> actions) {
        return new Outcome(intent, reply, SOURCE_RULE, List.of(), actions, List.of(), null);
    }

    private static AssistantCard card(ComplaintResponse d) {
        return AssistantCard.complaint(d.complaintId(), d.referenceNumber(), d.category(), d.status(), d.departmentName(),
                d.isEscalated(), d.updatedAt());
    }

    private static List<AssistantAction> complaintActions(ComplaintResponse d, String language) {
        return List.of(new AssistantAction(AssistantAction.OPEN_COMPLAINT,
                AssistantTexts.get(AssistantTexts.ACTION_OPEN_COMPLAINT, language), String.valueOf(d.complaintId())));
    }

    /** The quick buttons under most replies. */
    static List<AssistantAction> menu(User user, String language) {
        List<AssistantAction> actions = new ArrayList<>();
        actions.add(AssistantAction.ask(AssistantTexts.get(AssistantTexts.CHIP_WHERE, language)));
        if (user.getRole() == Role.CITIZEN) {
            actions.add(AssistantAction.ask(AssistantTexts.get(AssistantTexts.CHIP_REPORT, language)));
            actions.add(AssistantAction.ask(AssistantTexts.get(AssistantTexts.CHIP_REJECTED, language)));
        }
        actions.add(AssistantAction.ask(AssistantTexts.get(AssistantTexts.CHIP_TIME, language)));
        return actions;
    }
}
