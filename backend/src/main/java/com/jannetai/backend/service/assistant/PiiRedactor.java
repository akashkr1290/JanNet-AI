package com.jannetai.backend.service.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JanNet Sahayak: removes personal data from what the user typed BEFORE it is
 * stored (assistant_messages) or sent to ai-service / Gemini (the free tier may
 * use inputs to improve Google products).
 *
 * <p>Removed: e-mail addresses, Indian phone numbers, any other run of 9 or more
 * digits (Aadhaar, account or card numbers), house / flat numbers, a name after
 * "my name is" (also Hinglish / Hindi), and the signed-in user's own name.
 * Kept: complaint reference numbers (JN-2026-000123), PIN codes and ward
 * numbers - they describe the public place, not the person.
 */
public final class PiiRedactor {

    public static final String REMOVED = "[removed]";

    private static final Pattern REFERENCE = Pattern.compile("(?i)\\bJN[\\s-]?\\d{4}[\\s-]?\\d{1,6}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?91[\\s-]?|0)?[6-9]\\d{4}[\\s-]?\\d{5}(?!\\d)");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d(?:[\\s-]?\\d){8,}(?!\\d)");
    private static final Pattern HOUSE_NUMBER = Pattern.compile(
            "(?iu)\\b(?:house|h\\.?\\s?no|flat|makan|ghar)\\s*(?:no\\.?|number|num|#)?\\s*[:.-]?\\s*\\d+[\\w/-]*"
                    + "|(?:मकान|घर|फ्लैट)\\s*(?:नं\\.?|नंबर|संख्या)?\\s*[:.-]?\\s*\\d+[\\w/-]*");
    private static final Pattern MY_NAME_IS = Pattern.compile(
            "(?iu)(\\bmy name is|\\bi am called|\\bmera naam|\\bmeraa naam|मेरा नाम|माझे नाव|আমার নাম|என் பெயர்|నా పేరు|ನನ್ನ ಹೆಸರು)"
                    + "\\s+([\\p{L}\\p{M}.]+)(?:\\s+([\\p{L}\\p{M}.]+))?");
    /** Words that may follow a name and are not part of it ("mera naam Sunita hai"). */
    private static final Set<String> AFTER_NAME = Set.of(
            "hai", "he", "h", "and", "aur", "hoon", "hun", "here", "है", "हूँ", "हूं", "और", "आहे", "আছে", "এবং");

    private PiiRedactor() {
    }

    /**
     * @param text     what the user typed (may be null)
     * @param fullName the signed-in user's own name (may be null); each part of 3+ letters is removed
     */
    public static String redact(String text, String fullName) {
        if (text == null || text.isBlank()) {
            return text;
        }
        List<String> kept = new ArrayList<>();
        Matcher ref = REFERENCE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (ref.find()) {
            kept.add(ref.group());
            ref.appendReplacement(sb, Matcher.quoteReplacement("\u0000" + (kept.size() - 1) + "\u0000"));
        }
        ref.appendTail(sb);
        String out = sb.toString();

        out = EMAIL.matcher(out).replaceAll(REMOVED);
        out = PHONE.matcher(out).replaceAll(REMOVED);
        out = LONG_DIGITS.matcher(out).replaceAll(REMOVED);
        out = HOUSE_NUMBER.matcher(out).replaceAll(REMOVED);
        out = MY_NAME_IS.matcher(out).replaceAll(m -> {
            String second = m.group(3);
            String keep = second != null && AFTER_NAME.contains(second.toLowerCase(Locale.ROOT)) ? " " + second : "";
            return Matcher.quoteReplacement(m.group(1) + " " + REMOVED + keep);
        });
        out = removeOwnName(out, fullName);

        Matcher back = Pattern.compile("\u0000(\\d+)\u0000").matcher(out);
        StringBuilder restored = new StringBuilder();
        while (back.find()) {
            back.appendReplacement(restored, Matcher.quoteReplacement(kept.get(Integer.parseInt(back.group(1)))));
        }
        back.appendTail(restored);
        return restored.toString();
    }

    private static String removeOwnName(String text, String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return text;
        }
        String out = text;
        for (String part : fullName.trim().split("\\s+")) {
            String p = part.replaceAll("[^\\p{L}\\p{M}]", "");
            if (p.codePointCount(0, p.length()) < 3) {
                continue;
            }
            Pattern own = Pattern.compile("(?<![\\p{L}\\p{M}])" + Pattern.quote(p) + "(?![\\p{L}\\p{M}])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            out = own.matcher(out).replaceAll(REMOVED);
        }
        return out;
    }

    /** True when the text still contains something this class would remove (used by tests and logging checks). */
    public static boolean containsPersonalData(String text, String fullName) {
        return text != null && !text.equals(redact(text, fullName));
    }
}
