package com.jannetai.backend.service.assistant;

import com.jannetai.backend.entity.enums.ComplaintCategory;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JanNet Sahayak's intent router - fixed rules, no LLM. Most questions are
 * recognised here and answered from the user's own data or a help article,
 * which keeps answers instant, exact and inside Gemini's free limit.
 *
 * <p>Words are matched in English, Hinglish (romanised Hindi) and the app's
 * Indian languages. A keyword ending in {@code *} matches the start of a word
 * (Hindi/Tamil/... words take endings); any other keyword must match whole
 * words. A tapped suggestion chip (AssistantTexts CHIP_*) is recognised
 * exactly in every language.
 */
public final class IntentRouter {

    public enum Intent {
        GREETING, THANKS, EMERGENCY, COMPLAINT_STATUS, MY_COMPLAINTS, REJECTION_REASON,
        REPORT_DRAFT, REPORT_START, HELP_ARTICLE, OPEN_QUESTION
    }

    /**
     * @param referenceNumber normalised JN-YYYY-NNNNNN for COMPLAINT_STATUS
     * @param category        the problem type found in the text (REPORT_DRAFT, or a filter for MY_COMPLAINTS)
     * @param articleSlug     a help article the rule already chose (HELP_ARTICLE), else null
     */
    public record Routed(Intent intent, String referenceNumber, ComplaintCategory category, String articleSlug) {
        static Routed of(Intent intent) {
            return new Routed(intent, null, null, null);
        }
    }

    private static final Pattern REFERENCE = Pattern.compile("(?i)\\bJN[\\s-]?(\\d{4})[\\s-]?(\\d{1,6})\\b");

    private static final List<String> EMERGENCY = kw(
            "emergency", "fire", "on fire", "accident", "injured", "bleeding", "ambulance", "dying", "electric shock",
            "aag lagi", "aag lag gayi", "accident hua", "current laga", "आग", "आग लगी", "दुर्घटना", "एम्बुलेंस",
            "आपातकाल", "करंट", "अपघात", "আগুন", "দুর্ঘটনা", "অ্যাম্বুলেন্স", "தீ விபத்து", "விபத்து*", "ஆம்புலன்ஸ்",
            "అగ్ని ప్రమాదం", "ప్రమాదం", "అంబులెన్స్", "ಬೆಂಕಿ", "ಅಪಘಾತ*", "ಆಂಬ್ಯುಲೆನ್ಸ್");
    private static final List<String> GREETING = kw(
            "hi", "hii", "hello", "hey", "namaste", "namaskar", "good morning", "good evening", "good afternoon", "start",
            "help", "menu", "madad", "नमस्ते", "नमस्कार", "मदद", "सहायता", "मदत", "নমস্কার", "সাহায্য", "வணக்கம்",
            "உதவி", "నమస్కారం", "నమస్తే", "సహాయం", "ನಮಸ್ಕಾರ", "ನಮಸ್ತೆ", "ಸಹಾಯ");
    private static final List<String> THANKS = kw(
            "thanks", "thank you", "thank u", "thx", "ok thanks", "dhanyavad", "dhanyawad", "shukriya", "धन्यवाद",
            "शुक्रिया", "आभार", "ধন্যবাদ", "நன்றி", "ధన్యవాదాలు", "ಧನ್ಯವಾದ*");
    private static final List<String> APPEAL = kw(
            "appeal*", "apeel", "apil", "अपील", "আপিল", "மேல்முறையீடு*", "అప్పీల్*", "ಮೇಲ್ಮನವಿ*");
    private static final List<String> REJECTION = kw(
            "reject*", "not accepted", "mana kar", "अस्वीकार*", "अस्वीकृत", "रिजेक्ट", "नाकार*", "প্রত্যাখ্যা*",
            "নিরাকরি*", "நிராகரி*", "తిరస్కర*", "ತಿರಸ್ಕರ*", "ತಿರಸ್ಕಾರ*");
    private static final List<String> MEANING = kw(
            "mean", "means", "meaning", "matlab", "arth", "मतलब", "अर्थ", "মানে", "অর্থ", "பொருள்", "அர்த்தம்", "అర్థం",
            "ಅರ್ಥ*");
    private static final List<String> HOW = kw(
            "how", "how to", "what can", "which", "kaise", "kese", "kaisa", "kya kar", "कैसे", "कैसा", "कौन सी", "किन",
            "कसे", "कशी", "कसा", "कोणत्या", "কীভাবে", "কিভাবে", "কোন", "எப்படி", "எந்த", "ఎలా", "ఏ", "ಹೇಗೆ", "ಯಾವ");
    private static final List<String> MY = kw(
            "my", "mine", "mera", "meri", "mere", "hamara", "hamari", "मेरा", "मेरी", "मेरे", "हमारी", "हमारा", "माझी",
            "माझा", "माझे", "माझ्या", "আমার", "என்", "எனது", "என்னுடைய", "నా", "ನನ್ನ");
    private static final List<String> COMPLAINT_WORD = kw(
            "complaint*", "report*", "shikayat", "shikayaat", "शिकायत*", "तक्रार*", "অভিযোগ*", "புகார்*", "ఫిర్యాదు*",
            "ದೂರು*");
    private static final List<String> STATUS_WORD = kw(
            "status", "where", "update", "progress", "fixed", "done", "when", "kahan", "kahaan", "kab", "kya hua",
            "hua kya", "theek hua", "kitna hua", "कहाँ", "कहां", "कब", "क्या हुआ", "स्थिति", "स्टेटस", "ठीक हुआ", "कुठे",
            "केव्हा", "स्थिती", "কোথায়", "কবে", "অবস্থা", "எங்கே", "எப்போது", "நிலை", "ఎక్కడ", "ఎప్పుడు", "స్థితి",
            "ಎಲ್ಲಿದೆ", "ಎಲ್ಲಿ", "ಯಾವಾಗ", "ಸ್ಥಿತಿ");
    private static final List<String> REPORT_INTENT = kw(
            "i want to report", "want to complain", "file a complaint", "new complaint", "register complaint",
            "shikayat karni", "shikayat karna", "complaint karni", "complaint karna", "report karna", "report karni",
            "शिकायत करनी", "शिकायत करना", "शिकायत दर्ज करनी", "नई शिकायत", "तक्रार करायची", "नवीन तक्रार",
            "অভিযোগ করতে চাই", "নতুন অভিযোগ", "புகார் செய்ய வேண்டும்", "புதிய புகார்", "ఫిర్యాదు చేయాలి",
            "కొత్త ఫిర్యాదు", "ದೂರು ನೀಡಬೇಕು", "ಹೊಸ ದೂರು");

    private static final Map<ComplaintCategory, List<String>> CATEGORY = new LinkedHashMap<>();

    static {
        CATEGORY.put(ComplaintCategory.OPEN_MANHOLE, kw(
                "manhole*", "man hole", "sewer open", "gutter open", "gutter khula", "dhakkan", "ढक्कन", "मैनहोल*",
                "मॅनहोल*", "चेंबर खुला", "ম্যানহোল*", "மேன்ஹோல்*", "మ్యాన్‌హోల్*", "మ్యాన్హోల్*", "ಮ್ಯಾನ್‌ಹೋಲ್*", "ಮ್ಯಾನ್ಹೋಲ್*"));
        CATEGORY.put(ComplaintCategory.BROKEN_STREET_LIGHT, kw(
                "street light*", "streetlight*", "street lamp*", "light not working", "light kharab", "light band",
                "batti", "bijli ka khamba", "बत्ती", "स्ट्रीट लाइट*", "लाइट खराब", "लाइट ख़राब", "लाइट बंद", "पथदिव*",
                "रस्त्यावरील दिवा", "রাস্তার আলো", "স্ট্রিট লাইট", "தெருவிளக்கு*", "தெரு விளக்கு*", "வீதி விளக்கு*",
                "వీధి దీప*", "వీధిదీప*", "ಬೀದಿ ದೀಪ*", "ಬೀದಿದೀಪ*"));
        CATEGORY.put(ComplaintCategory.POTHOLE, kw(
                "pothole*", "pot hole*", "broken road", "road broken", "road damaged", "gaddha", "gadda", "gaddhe",
                "gadhha", "khadda", "sadak tooti", "sadak toot", "road toot", "गड्ढ*", "गढ्ढ*", "गड्ड*", "खड्ड*",
                "टूटी सड़क", "सड़क टूट*", "রাস্তার গর্ত", "গর্ত", "குழி*", "గుంత*", "గుంట*", "ಗುಂಡಿ*"));
        CATEGORY.put(ComplaintCategory.GARBAGE_OVERFLOW, kw(
                "garbage", "trash", "rubbish", "waste", "dustbin", "dump", "kachra", "kachara", "kooda", "kuda", "koodha",
                "kudha", "gandagi", "कूड़ा", "कूडा", "कूड़े", "कचरा", "कचरे", "गंदगी", "কাচরা", "আবর্জনা", "ময়লা",
                "குப்பை*", "చెత్త*", "ಕಸ*"));
        CATEGORY.put(ComplaintCategory.WATER_LEAKAGE, kw(
                "leak*", "water leak*", "pipe burst", "pipe leak*", "pipe broken", "pipe toot", "paani beh", "pani beh",
                "paani bah", "pani bah", "pani leak", "रिसाव", "लीक*", "पानी बह*", "पाइप फट*", "पाइप टूट*", "गळती",
                "পাইপ ফেটে", "জল পড়", "লিক*", "கசிவு*", "లీక*", "ಸೋರಿಕೆ*"));
        CATEGORY.put(ComplaintCategory.ILLEGAL_CONSTRUCTION, kw(
                "illegal construction", "encroach*", "kabza", "kabja", "avaidh nirman", "अवैध निर्माण", "अतिक्रमण",
                "कब्जा", "कब्ज़ा", "बेकायदेशीर बांधकाम", "अवैध बांधकाम", "অবৈধ নির্মাণ", "দখল", "சட்டவிரோத கட்டுமான*",
                "ஆக்கிரமிப்பு*", "అక్రమ నిర్మాణ*", "ఆక్రమణ*", "ಅಕ್ರಮ ನಿರ್ಮಾಣ*", "ಒತ್ತುವರಿ*"));
        CATEGORY.put(ComplaintCategory.GENERAL, kw(
                "drain*", "sewage", "waterlogging", "water logging", "nala", "naala", "naali", "nali", "jal bharav",
                "नाली", "नाला", "जलभराव", "सीवर", "गटार", "নর্দমা", "சாக்கடை*", "కాలువ*", "ಚರಂಡಿ*"));
    }

    private IntentRouter() {
    }

    public static Routed route(String text) {
        String raw = text == null ? "" : text.trim();
        String norm = normalise(raw);
        if (norm.isBlank()) {
            return Routed.of(Intent.GREETING);
        }

        Matcher ref = REFERENCE.matcher(raw);
        if (ref.find()) {
            String reference = "JN-%s-%06d".formatted(ref.group(1), Integer.parseInt(ref.group(2)));
            return new Routed(Intent.COMPLAINT_STATUS, reference, null, null);
        }

        Routed chip = chip(norm);
        if (chip != null) {
            return chip;
        }
        int words = norm.trim().split(" ").length;
        if (any(norm, EMERGENCY)) {
            return Routed.of(Intent.EMERGENCY);
        }
        ComplaintCategory category = category(norm);
        if (category == null && words <= 4 && any(norm, GREETING)) {
            return Routed.of(Intent.GREETING);
        }
        if (words <= 6 && any(norm, THANKS)) {
            return Routed.of(Intent.THANKS);
        }
        if (any(norm, APPEAL)) {
            return new Routed(Intent.HELP_ARTICLE, null, null, "appeal");
        }
        if (any(norm, REJECTION)) {
            return Routed.of(Intent.REJECTION_REASON);
        }
        if (any(norm, MEANING)) {
            return Routed.of(Intent.HELP_ARTICLE);
        }

        boolean mine = any(norm, MY);
        boolean statusQuestion = any(norm, STATUS_WORD);
        if (category != null && mine && statusQuestion) {
            // "is my pothole fixed yet?" - a question about an existing complaint, not a new one
            return new Routed(Intent.MY_COMPLAINTS, null, category, null);
        }
        if (category != null) {
            // "there is a pothole on my road" -> draft a report; "how do I report a pothole?" -> the how-to article
            return any(norm, HOW)
                    ? new Routed(Intent.HELP_ARTICLE, null, category, "how-to-report")
                    : new Routed(Intent.REPORT_DRAFT, null, category, null);
        }
        if (mine && (any(norm, COMPLAINT_WORD) || statusQuestion)) {
            return Routed.of(Intent.MY_COMPLAINTS);
        }
        if (any(norm, REPORT_INTENT)) {
            return Routed.of(Intent.REPORT_START);
        }
        return Routed.of(Intent.OPEN_QUESTION);
    }

    /** The problem type named in the text, or null. */
    public static ComplaintCategory category(String normalised) {
        for (Map.Entry<ComplaintCategory, List<String>> e : CATEGORY.entrySet()) {
            if (any(normalised, e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    private static Routed chip(String norm) {
        if (matchesChip(norm, AssistantTexts.CHIP_WHERE)) {
            return Routed.of(Intent.MY_COMPLAINTS);
        }
        if (matchesChip(norm, AssistantTexts.CHIP_REJECTED)) {
            return Routed.of(Intent.REJECTION_REASON);
        }
        if (matchesChip(norm, AssistantTexts.CHIP_REPORT)) {
            return new Routed(Intent.HELP_ARTICLE, null, null, "how-to-report");
        }
        if (matchesChip(norm, AssistantTexts.CHIP_TIME)) {
            return new Routed(Intent.HELP_ARTICLE, null, null, "timelines");
        }
        if (matchesChip(norm, AssistantTexts.CHIP_APPEAL)) {
            return new Routed(Intent.HELP_ARTICLE, null, null, "appeal");
        }
        return null;
    }

    private static boolean matchesChip(String norm, String key) {
        for (String chipText : AssistantTexts.allLanguages(key).values()) {
            if (normalise(chipText).equals(norm)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lower case, Unicode NFC, punctuation to spaces, single spaces, with one
     * leading and trailing space so whole-word checks are simple contains().
     */
    public static String normalise(String text) {
        if (text == null) {
            return " ";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        // keep letters, combining marks (Indic vowel signs), digits and the zero-width joiners Indic scripts use
        s = s.replaceAll("[^\\p{L}\\p{M}\\p{N}\\u200C\\u200D]+", " ").trim();
        return " " + s + " ";
    }

    static boolean any(String normalised, List<String> keywords) {
        for (String k : keywords) {
            if (k.endsWith("*")) {
                if (normalised.contains(" " + k.substring(0, k.length() - 1))) {
                    return true;
                }
            } else if (normalised.contains(" " + k + " ")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> kw(String... words) {
        return java.util.Arrays.stream(words)
                .map(w -> {
                    boolean prefix = w.endsWith("*");
                    String base = normalise(prefix ? w.substring(0, w.length() - 1) : w).trim();
                    return prefix ? base + "*" : base;
                })
                .toList();
    }
}
