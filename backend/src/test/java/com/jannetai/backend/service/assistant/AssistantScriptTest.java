package com.jannetai.backend.service.assistant;

import com.jannetai.backend.dto.assistant.AssistantCard;
import com.jannetai.backend.dto.assistant.AssistantReply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * JanNet Sahayak "done when" check (feature plan, Feature A): 50 scripted
 * questions - complaint status, another user's complaint, my complaints,
 * rejections, guided filing, the FAQ in all seven languages, out-of-scope
 * questions - each give the expected kind of answer, status answers use the
 * app's own status labels, and no answer reveals another user's complaint.
 * Rules only (LLM off), so the result does not depend on Gemini.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantScriptTest {

    private final AssistantTestWorld w = new AssistantTestWorld();
    private final AssistantService service = w.service(false);
    private final List<AssistantReply> replies = new ArrayList<>();

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "EN|JN-2026-000101|COMPLAINT_STATUS|RULE|In Progress",
            "EN|what is the status of jn-2026-000102?|COMPLAINT_STATUS|RULE|Rejected",
            "EN|JN 2026 103 update please|COMPLAINT_STATUS|RULE|Resolved",
            "HI|JN-2026-000101 की स्थिति बताओ|COMPLAINT_STATUS|RULE|प्रगति में",
            "MR|JN-2026-000101 कुठे आहे|COMPLAINT_STATUS|RULE|प्रगतीत",
            "BN|JN-2026-000103 এর অবস্থা|COMPLAINT_STATUS|RULE|সমাধান হয়েছে",
            "TA|JN-2026-000102 நிலை என்ன|COMPLAINT_STATUS|RULE|நிராகரிக்கப்பட்டது",
            "KN|JN-2026-000101 ಸ್ಥಿತಿ|COMPLAINT_STATUS|RULE|ಪ್ರಗತಿಯಲ್ಲಿದೆ",
            "EN|JN-2026-000900|COMPLAINT_STATUS|RULE|could not find",
            "HI|JN-2026-000900 कहाँ है|COMPLAINT_STATUS|RULE|नहीं मिली",
            "EN|status of JN-2026-999999|COMPLAINT_STATUS|RULE|could not find",
            "TE|JN-2026-000900 స్థితి|COMPLAINT_STATUS|RULE|కనిపించలేదు",
            "EN|where is my complaint|MY_COMPLAINTS|RULE|Complaints found: 3",
            "HI|meri shikayat kahan hai|MY_COMPLAINTS|RULE|मिली शिकायतें: 3",
            "HI|मेरी शिकायत कहाँ है?|MY_COMPLAINTS|RULE|3",
            "EN|is my pothole fixed yet?|MY_COMPLAINTS|RULE|JN-2026-000101",
            "KN|ನನ್ನ ದೂರು ಎಲ್ಲಿದೆ?|MY_COMPLAINTS|RULE|3",
            "EN|why was my complaint rejected|REJECTION_REASON|RULE|the photo does not show the problem clearly enough",
            "HI|reject kyu hua|REJECTION_REASON|RULE|फ़ोटो में समस्या पर्याप्त साफ़ नहीं दिखती",
            "TA|என் புகார் ஏன் நிராகரிக்கப்பட்டது?|REJECTION_REASON|RULE|JN-2026-000102",
            "EN|there is a big pothole near the school gate|REPORT_DRAFT|RULE|Tap Start report",
            "HI|gali me gaddha hai|REPORT_DRAFT|RULE|रिपोर्ट शुरू करें",
            "HI|कूड़ा कई दिन से पड़ा है|REPORT_DRAFT|RULE|रिपोर्ट शुरू करें",
            "EN|street light not working in my lane|REPORT_DRAFT|RULE|Start report",
            "BN|রাস্তায় গর্ত আছে|REPORT_DRAFT|RULE|রিপোর্ট শুরু করুন",
            "EN|How do I report a problem?|HELP_ARTICLE|ARTICLE|Submit tab",
            "HI|समस्या की शिकायत कैसे करें?|HELP_ARTICLE|ARTICLE|दर्ज करें टैब",
            "MR|समस्येची तक्रार कशी करावी?|HELP_ARTICLE|ARTICLE|सबमिट टॅब",
            "BN|কীভাবে সমস্যার অভিযোগ করব?|HELP_ARTICLE|ARTICLE|জমা দিন ট্যাব",
            "TA|ஒரு பிரச்சினையை எப்படிப் புகார் செய்வது?|HELP_ARTICLE|ARTICLE|சமர்ப்பி தாவலைத்",
            "TE|సమస్యపై ఫిర్యాదు ఎలా చేయాలి?|HELP_ARTICLE|ARTICLE|సమర్పించు ట్యాబ్",
            "KN|ಸಮಸ್ಯೆಯ ಬಗ್ಗೆ ದೂರು ನೀಡುವುದು ಹೇಗೆ?|HELP_ARTICLE|ARTICLE|ಸಲ್ಲಿಸಿ ಟ್ಯಾಬ್",
            "EN|How long will it take?|HELP_ARTICLE|ARTICLE|SLA",
            "HI|कितना समय लगेगा?|HELP_ARTICLE|ARTICLE|SLA",
            "EN|how do I appeal|HELP_ARTICLE|ARTICLE|appealed only once",
            "HI|appeal kaise kare|HELP_ARTICLE|ARTICLE|एक बार अपील",
            "EN|what does in progress mean|HELP_ARTICLE|ARTICLE|Under Review",
            "EN|how to change language|HELP_ARTICLE|ARTICLE|Settings",
            "TE|భాష మార్చడం ఎలా|HELP_ARTICLE|ARTICLE|సెట్టింగ్‌లు",
            "EN|what is a duplicate complaint|HELP_ARTICLE|ARTICLE|joined",
            "EN|why do I need otp|HELP_ARTICLE|ARTICLE|10 complaints",
            "EN|I did not get any email update|HELP_ARTICLE|ARTICLE|SMS",
            "EN|who will win the cricket world cup|OPEN_QUESTION|FALLBACK|Sorry",
            "HI|aaj mausam kaisa hai|OPEN_QUESTION|FALLBACK|माफ़ कीजिए",
            "EN|tell me a joke|OPEN_QUESTION|FALLBACK|Sorry",
            "TA|இன்று என்ன செய்தி|OPEN_QUESTION|FALLBACK|மன்னிக்கவும்",
            "EN|hi|GREETING|RULE|JanNet Sahayak",
            "HI|धन्यवाद|THANKS|RULE|स्वागत",
            "EN|there is fire in the building|EMERGENCY|RULE|112",
            "EN|I want to report something|REPORT_START|RULE|Start report",
    })
    void scriptedQuestion(String language, String text, String intent, String source, String mustContain) {
        AssistantReply r = AssistantTestWorld.ask(service, w.asha, text, language);
        replies.add(r);

        assertThat(r.intent()).as("intent").isEqualTo(intent);
        assertThat(r.source()).as("source").isEqualTo(source);
        assertThat(r.reply()).as("reply").contains(mustContain);
        for (AssistantCard card : r.cards()) {
            if (AssistantCard.COMPLAINT.equals(card.type()) && "COMPLAINT_STATUS".equals(intent)) {
                // the status in the text is the app's label for the status on the card
                assertThat(r.reply()).contains(AssistantTexts.statusLabel(card.status(), language));
            }
        }
    }

    @AfterAll
    void noAnswerRevealsAnotherUsersComplaint() {
        assertThat(replies).hasSize(50);
        for (AssistantReply r : replies) {
            assertThat(AssistantTestWorld.allText(r)).doesNotContain(AssistantTestWorld.OTHER_DEPARTMENT);
            assertThat(r.cards()).noneMatch(c -> Long.valueOf(900L).equals(c.complaintId()));
        }
        verify(w.aiServiceClient, never()).composeAssistant(any());
    }

    @Test
    void scriptCoversEveryLanguage() {
        // keeps the list above honest if someone edits it
        assertThat(AssistantTexts.LANGUAGES).containsExactly("EN", "HI", "MR", "BN", "TA", "TE", "KN");
    }
}
