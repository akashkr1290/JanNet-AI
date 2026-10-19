package com.jannetai.backend.service.assistant;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.service.assistant.IntentRouter.Intent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** JanNet Sahayak: fixed-rule routing in English, Hinglish and the app's Indian languages. */
class IntentRouterTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            // greetings, thanks, emergency
            "hi|GREETING|-|-",
            "Hello there|GREETING|-|-",
            "नमस्ते|GREETING|-|-",
            "thanks|THANKS|-|-",
            "धन्यवाद|THANKS|-|-",
            "there is fire in the building|EMERGENCY|-|-",
            "आग लगी है|EMERGENCY|-|-",
            // my complaints
            "where is my complaint|MY_COMPLAINTS|-|-",
            "meri shikayat kahan hai|MY_COMPLAINTS|-|-",
            "मेरी शिकायत कहाँ है?|MY_COMPLAINTS|-|-",
            "माझी तक्रार कुठे आहे?|MY_COMPLAINTS|-|-",
            "আমার অভিযোগ কোথায়?|MY_COMPLAINTS|-|-",
            "என் புகார் எங்கே உள்ளது?|MY_COMPLAINTS|-|-",
            "నా ఫిర్యాదు ఎక్కడ ఉంది?|MY_COMPLAINTS|-|-",
            "ನನ್ನ ದೂರು ಎಲ್ಲಿದೆ?|MY_COMPLAINTS|-|-",
            "is my pothole fixed yet?|MY_COMPLAINTS|POTHOLE|-",
            // rejection and appeal
            "why was my complaint rejected|REJECTION_REASON|-|-",
            "reject kyu hua|REJECTION_REASON|-|-",
            "मेरी शिकायत अस्वीकार क्यों हुई|REJECTION_REASON|-|-",
            "என் புகார் ஏன் நிராகரிக்கப்பட்டது?|REJECTION_REASON|-|-",
            "my complaint got rejected, how do I appeal|HELP_ARTICLE|-|appeal",
            "appeal kaise kare|HELP_ARTICLE|-|appeal",
            // guided filing
            "there is a big pothole near the school gate|REPORT_DRAFT|POTHOLE|-",
            "gali me gaddha hai|REPORT_DRAFT|POTHOLE|-",
            "सड़क पर बड़ा गड्ढा है|REPORT_DRAFT|POTHOLE|-",
            "रस्त्यावर खड्डा आहे|REPORT_DRAFT|POTHOLE|-",
            "রাস্তায় গর্ত আছে|REPORT_DRAFT|POTHOLE|-",
            "சாலையில் குழி உள்ளது|REPORT_DRAFT|POTHOLE|-",
            "రోడ్డుపై గుంత ఉంది|REPORT_DRAFT|POTHOLE|-",
            "ರಸ್ತೆಯಲ್ಲಿ ಗುಂಡಿ ಇದೆ|REPORT_DRAFT|POTHOLE|-",
            "garbage is lying near the market for 5 days|REPORT_DRAFT|GARBAGE_OVERFLOW|-",
            "kooda pada hai market ke paas|REPORT_DRAFT|GARBAGE_OVERFLOW|-",
            "कूड़ा कई दिन से पड़ा है|REPORT_DRAFT|GARBAGE_OVERFLOW|-",
            "street light not working in my lane|REPORT_DRAFT|BROKEN_STREET_LIGHT|-",
            "open manhole on the main road|REPORT_DRAFT|OPEN_MANHOLE|-",
            "pipe burst and water is leaking|REPORT_DRAFT|WATER_LEAKAGE|-",
            "naali band hai paani bhar gaya|REPORT_DRAFT|GENERAL|-",
            "how do I report a pothole?|HELP_ARTICLE|POTHOLE|how-to-report",
            "I want to report something|REPORT_START|-|-",
            // help and open questions
            "what does in progress mean|HELP_ARTICLE|-|-",
            "स्थिति का मतलब क्या है|HELP_ARTICLE|-|-",
            "how to change language|OPEN_QUESTION|-|-",
            "who will win the cricket world cup|OPEN_QUESTION|-|-",
    })
    void routes(String text, Intent intent, ComplaintCategory category, String slug) {
        IntentRouter.Routed r = IntentRouter.route(text);
        assertThat(r.intent()).isEqualTo(intent);
        assertThat(r.category()).isEqualTo(category);
        assertThat(r.articleSlug()).isEqualTo(slug);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "status of JN-2026-000123|JN-2026-000123",
            "jn 2026 45 kahan hai|JN-2026-000045",
            "JN-2026-123 rejected kyu|JN-2026-000123",
    })
    void referenceNumberWinsOverEverythingElse(String text, String reference) {
        IntentRouter.Routed r = IntentRouter.route(text);
        assertThat(r.intent()).isEqualTo(Intent.COMPLAINT_STATUS);
        assertThat(r.referenceNumber()).isEqualTo(reference);
    }

    @Test
    void everySuggestionChipRoutesInEveryLanguage() {
        for (String lang : AssistantTexts.LANGUAGES) {
            assertThat(IntentRouter.route(AssistantTexts.get(AssistantTexts.CHIP_WHERE, lang)).intent())
                    .as(lang).isEqualTo(Intent.MY_COMPLAINTS);
            assertThat(IntentRouter.route(AssistantTexts.get(AssistantTexts.CHIP_REJECTED, lang)).intent())
                    .as(lang).isEqualTo(Intent.REJECTION_REASON);
            assertThat(IntentRouter.route(AssistantTexts.get(AssistantTexts.CHIP_REPORT, lang)).articleSlug())
                    .as(lang).isEqualTo("how-to-report");
            assertThat(IntentRouter.route(AssistantTexts.get(AssistantTexts.CHIP_TIME, lang)).articleSlug())
                    .as(lang).isEqualTo("timelines");
            assertThat(IntentRouter.route(AssistantTexts.get(AssistantTexts.CHIP_APPEAL, lang)).articleSlug())
                    .as(lang).isEqualTo("appeal");
        }
    }

    // ---- Sahayak v2 ----

    @ParameterizedTest(name = "staff: {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "what is overdue for me today|STAFF_OVERDUE",
            "aaj kya overdue hai|STAFF_OVERDUE",
            "how many complaints are past the deadline|STAFF_OVERDUE",
            "कौन सी शिकायतें देरी से हैं|STAFF_OVERDUE",
            "sla breaches|STAFF_OVERDUE",
            "complaints waiting for an officer|STAFF_UNASSIGNED",
            "assign nahi hua|STAFF_UNASSIGNED",
            "which officer has the most work|STAFF_WORKLOAD",
            "अधिकारियों का कार्यभार|STAFF_WORKLOAD",
            "department wise workload|STAFF_WORKLOAD",
            "this week's numbers|STAFF_STATS",
            "is hafte ke aankde|STAFF_STATS",
            "kitni complaints aayi|STAFF_STATS",
            "my work for today|STAFF_TODAY",
            "aaj ka mera kaam|STAFF_TODAY",
            "what should I do today|STAFF_TODAY",
            "where is my complaint|MY_COMPLAINTS",
            "hi|GREETING",
            "there is a pothole near the bus stand|REPORT_DRAFT",
    })
    void staffWorkQuestions(String text, Intent intent) {
        assertThat(IntentRouter.route(text, true).intent()).isEqualTo(intent);
    }

    @ParameterizedTest(name = "citizen: {0}")
    @CsvSource(delimiter = '|', value = {
            "aaj gaddha dekha|REPORT_DRAFT",
            "meri complaint late ho gayi|MY_COMPLAINTS",
            "is hafte ke aankde|OPEN_QUESTION",
            "kaam kab shuru hoga|OPEN_QUESTION",
    })
    void staffWordsNeverChangeACitizensRoute(String text, Intent intent) {
        assertThat(IntentRouter.route(text).intent()).isEqualTo(intent);
        assertThat(IntentRouter.route(text, false).intent()).isEqualTo(intent);
    }

    @Test
    void everyStaffChipRoutesInEveryLanguage() {
        for (String lang : AssistantTexts.LANGUAGES) {
            assertThat(IntentRouter.route(AssistantTexts.get(AssistantTexts.CHIP_TODAY, lang), true).intent())
                    .as(lang).isEqualTo(Intent.STAFF_TODAY);
            assertThat(IntentRouter.route(AssistantTexts.get(AssistantTexts.CHIP_OVERDUE, lang), true).intent())
                    .as(lang).isEqualTo(Intent.STAFF_OVERDUE);
            assertThat(IntentRouter.route(AssistantTexts.get(AssistantTexts.CHIP_UNASSIGNED, lang), true).intent())
                    .as(lang).isEqualTo(Intent.STAFF_UNASSIGNED);
            assertThat(IntentRouter.route(AssistantTexts.get(AssistantTexts.CHIP_WORKLOAD, lang), true).intent())
                    .as(lang).isEqualTo(Intent.STAFF_WORKLOAD);
            assertThat(IntentRouter.route(AssistantTexts.get(AssistantTexts.CHIP_STATS, lang), true).intent())
                    .as(lang).isEqualTo(Intent.STAFF_STATS);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "JN-2026-000123 history|JN-2026-000123",
            "jn 2026 45 ka itihas batao|JN-2026-000045",
            "JN-2026-000123 का इतिहास|JN-2026-000123",
    })
    void historyOfAComplaintForEveryone(String text, String reference) {
        for (boolean staff : new boolean[] {false, true}) {
            IntentRouter.Routed r = IntentRouter.route(text, staff);
            assertThat(r.intent()).isEqualTo(Intent.COMPLAINT_HISTORY);
            assertThat(r.referenceNumber()).isEqualTo(reference);
        }
    }

    @Test
    void emptyTextIsAGreeting() {
        assertThat(IntentRouter.route("  ").intent()).isEqualTo(Intent.GREETING);
        assertThat(IntentRouter.route(null).intent()).isEqualTo(Intent.GREETING);
    }

    @Test
    void normaliseKeepsIndicVowelSignsAndDropsPunctuation() {
        assertThat(IntentRouter.normalise("मेरी शिकायत कहाँ है?")).isEqualTo(" मेरी शिकायत कहाँ है ");
        assertThat(IntentRouter.normalise("Where, IS my complaint?!")).isEqualTo(" where is my complaint ");
    }
}
