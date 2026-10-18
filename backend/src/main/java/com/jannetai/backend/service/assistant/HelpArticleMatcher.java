package com.jannetai.backend.service.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks the help article a question is about, from the articles' keyword
 * phrases (help_articles.keywords, V38). Rows of the user's language and the
 * English rows are both used, so Hinglish typed with the app in English or
 * Hindi still matches.
 *
 * <ul>
 *   <li>{@code keywordScore}: whole keyword phrases found in the question, a
 *       longer phrase counting more. An article with a keyword score of at
 *       least 1 answers the question directly (source ARTICLE).</li>
 *   <li>{@code overlapScore}: plain word overlap with the title and keywords -
 *       only used to choose which articles Gemini may use for an open question.</li>
 * </ul>
 */
public final class HelpArticleMatcher {

    /** One help_articles row (only the fields matching needs). */
    public record Entry(String slug, String language, String title, String body, String keywords, int sortOrder) {
    }

    public record Scored(String slug, int keywordScore, int overlapScore, int sortOrder) {
    }

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "the", "and", "for", "how", "what", "are", "can", "you", "with", "this", "that", "will", "does", "why",
            "when", "where", "from", "have", "not", "your", "about", "there", "please", "tell", "want", "need", "who",
            "whom", "whose", "which", "anybody", "anyone", "someone", "kaun", "kab", "kahan",
            "hai", "kya", "kaise", "mein", "mera", "meri", "mujhe", "nahi", "aur", "kar", "karna", "hota",
            "है", "हैं", "क्या", "का", "की", "के", "में", "से", "को", "और", "कैसे", "मेरा", "मेरी", "मुझे", "नहीं", "करें",
            "आहे", "काय", "कसे", "माझी", "আমি", "আমার", "কী", "என்", "என்ன", "నా", "ఏమి", "ನನ್ನ", "ಏನು"));

    private HelpArticleMatcher() {
    }

    public static List<Scored> rank(String question, List<Entry> entries) {
        String norm = IntentRouter.normalise(question);
        Set<String> questionWords = contentWords(norm);
        Map<String, Scored> bySlug = new HashMap<>();
        for (Entry e : entries) {
            int keyword = keywordScore(norm, e);
            int overlap = overlap(questionWords, e);
            Scored current = new Scored(e.slug(), keyword, overlap, e.sortOrder());
            bySlug.merge(e.slug(), current, (a, b) -> new Scored(a.slug(),
                    Math.max(a.keywordScore(), b.keywordScore()), Math.max(a.overlapScore(), b.overlapScore()),
                    Math.min(a.sortOrder(), b.sortOrder())));
        }
        List<Scored> ranked = new ArrayList<>(bySlug.values());
        ranked.sort(Comparator.comparingInt(Scored::keywordScore).reversed()
                .thenComparing(Comparator.comparingInt(Scored::overlapScore).reversed())
                .thenComparingInt(Scored::sortOrder));
        return ranked;
    }

    /** The article that answers the question by its keywords, or null. */
    public static String best(String question, List<Entry> entries) {
        List<Scored> ranked = rank(question, entries);
        return !ranked.isEmpty() && ranked.get(0).keywordScore() >= 1 ? ranked.get(0).slug() : null;
    }

    static int keywordScore(String normalisedQuestion, Entry e) {
        int score = 0;
        String title = IntentRouter.normalise(e.title());
        if (title.equals(normalisedQuestion)) {
            score += 10;
        }
        for (String phrase : e.keywords().split(",")) {
            String p = IntentRouter.normalise(phrase).trim();
            if (!p.isEmpty() && normalisedQuestion.contains(" " + p + " ")) {
                score += p.split(" ").length;
            }
        }
        return score;
    }

    private static int overlap(Set<String> questionWords, Entry e) {
        if (questionWords.isEmpty()) {
            return 0;
        }
        Set<String> articleWords = contentWords(IntentRouter.normalise(e.title() + " " + e.keywords()));
        int n = 0;
        for (String w : questionWords) {
            if (articleWords.contains(w)) {
                n++;
            }
        }
        return n;
    }

    private static Set<String> contentWords(String normalised) {
        Set<String> out = new HashSet<>();
        for (String w : normalised.trim().split(" ")) {
            if (w.codePointCount(0, w.length()) >= 3 && !STOPWORDS.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }
}
