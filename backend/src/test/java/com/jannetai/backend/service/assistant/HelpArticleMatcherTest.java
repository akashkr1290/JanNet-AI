package com.jannetai.backend.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** JanNet Sahayak: choosing the help article a question is about (keywords from V38). */
class HelpArticleMatcherTest {

    private static final List<HelpArticleMatcher.Entry> ARTICLES = List.of(
            new HelpArticleMatcher.Entry("how-to-report", "EN", "How do I report a problem?", "Open the Submit tab.",
                    "how to report, how do i report, report a problem, shikayat kaise", 10),
            new HelpArticleMatcher.Entry("how-to-report", "HI", "समस्या की शिकायत कैसे करें?", "दर्ज करें टैब खोलें।",
                    "शिकायत कैसे, शिकायत दर्ज, shikayat kaise", 10),
            new HelpArticleMatcher.Entry("timelines", "EN", "How long will it take?", "Every department has a time limit.",
                    "how long, how many days, time limit, kitne din, kab tak", 90),
            new HelpArticleMatcher.Entry("language", "EN", "Changing the language", "Open Settings.",
                    "language, change language, bhasha", 180),
            new HelpArticleMatcher.Entry("privacy", "EN", "My personal data", "In Settings you can delete your account.",
                    "privacy, delete account, delete my account, my data", 200));

    @Test
    void keywordPhraseSelectsTheArticle() {
        assertThat(HelpArticleMatcher.best("kitne din lagenge?", ARTICLES)).isEqualTo("timelines");
        assertThat(HelpArticleMatcher.best("How to change language", ARTICLES)).isEqualTo("language");
        assertThat(HelpArticleMatcher.best("can I delete my account", ARTICLES)).isEqualTo("privacy");
        assertThat(HelpArticleMatcher.best("शिकायत कैसे करें", ARTICLES)).isEqualTo("how-to-report");
    }

    @Test
    void exactTitleWins() {
        assertThat(HelpArticleMatcher.rank("How long will it take?", ARTICLES).get(0).keywordScore()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void longerPhrasesCountMore() {
        List<HelpArticleMatcher.Scored> ranked = HelpArticleMatcher.rank("how do i report a problem", ARTICLES);
        assertThat(ranked.get(0).slug()).isEqualTo("how-to-report");
        assertThat(ranked.get(0).keywordScore()).isGreaterThan(3);
    }

    @Test
    void unrelatedQuestionHasNoArticle() {
        assertThat(HelpArticleMatcher.best("who will win the cricket world cup", ARTICLES)).isNull();
    }

    @Test
    void wordOverlapIsOnlyForChoosingAiSources() {
        List<HelpArticleMatcher.Scored> ranked = HelpArticleMatcher.rank("is my data safe with you", ARTICLES);
        assertThat(ranked.get(0).slug()).isEqualTo("privacy");
        assertThat(ranked.get(0).keywordScore()).isEqualTo(2); // "my data" (two words)
        assertThat(HelpArticleMatcher.rank("what about the account settings", ARTICLES).get(0).keywordScore()).isZero();
    }

    @Test
    void oneScorePerArticleAcrossLanguages() {
        assertThat(HelpArticleMatcher.rank("shikayat kaise", ARTICLES))
                .extracting(HelpArticleMatcher.Scored::slug).containsOnlyOnce("how-to-report");
    }
}
