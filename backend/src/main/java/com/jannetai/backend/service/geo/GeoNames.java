package com.jannetai.backend.service.geo;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Name matching between Google's address names and LGD names, which are
 * spelled differently for the same place: "Jolhabigha" (Google / citizen)
 * vs "Jolah Bigha" (LGD), "Tikari" vs "Tekari", "Gaziabad" vs "Ghaziabad".
 *
 * <p>{@link #normalize} must give exactly the same key as the importer
 * (database/tools/lgd_import/lgd_import.py {@code name_key}) because it is
 * compared with geo_villages.name_key and ward_localities.locality_key.
 *
 * <p>Used by {@link AreaResolver} (Google address -> village / body / ward)
 * and {@link AdminAreaService} (village search). Pure JDK, no Spring.
 */
public final class GeoNames {

    private GeoNames() {
    }

    /** Lower-case ASCII letters and digits only: "Jolah Bigha" -> "jolahbigha". */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Sound-alike key for Indian place names written in English: keeps the
     * first letter, then drops vowels and 'h' and collapses repeated letters,
     * after unifying common spelling swaps (w/v, ph/f, z/j, q/k, ee/i, oo/u).
     * "jolahbigha" and "jolhabigha" both give "jlbg"; "tikari" and "tekari" give "tkr".
     */
    public static String skeleton(String name) {
        String key = normalize(name)
                .replace("ph", "f").replace("w", "v").replace("z", "j").replace("q", "k")
                .replace("ee", "i").replace("oo", "u");
        if (key.isEmpty()) {
            return key;
        }
        StringBuilder out = new StringBuilder().append(key.charAt(0));
        char last = key.charAt(0);
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if ("aeiouyh".indexOf(c) >= 0 || c == last) {
                last = c;
                continue;
            }
            out.append(c);
            last = c;
        }
        return out.toString();
    }

    /**
     * 0..1 similarity used to rank candidates: 1.0 same key, 0.95 same
     * sound-alike skeleton (keys of 4+ letters, skeleton of 3+), otherwise Jaro-Winkler
     * of the keys. {@link AreaResolver} treats >= {@link #STRONG} as a match
     * worth proposing and >= {@link #WEAK} as a list candidate.
     */
    public static double similarity(String a, String b) {
        String ka = normalize(a);
        String kb = normalize(b);
        if (ka.isEmpty() || kb.isEmpty()) {
            return 0;
        }
        if (ka.equals(kb)) {
            return 1.0;
        }
        String sa = skeleton(ka);
        if (ka.length() >= 4 && kb.length() >= 4 && sa.length() >= 3 && sa.equals(skeleton(kb))) {
            return 0.95;
        }
        return jaroWinkler(ka, kb);
    }

    public static final double STRONG = 0.93;
    public static final double WEAK = 0.85;

    static double jaroWinkler(String s, String t) {
        int sl = s.length();
        int tl = t.length();
        int window = Math.max(0, Math.max(sl, tl) / 2 - 1);
        boolean[] sm = new boolean[sl];
        boolean[] tm = new boolean[tl];
        int matches = 0;
        for (int i = 0; i < sl; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(i + window + 1, tl);
            for (int j = from; j < to; j++) {
                if (!tm[j] && s.charAt(i) == t.charAt(j)) {
                    sm[i] = true;
                    tm[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int transpositions = 0;
        for (int i = 0, j = 0; i < sl; i++) {
            if (!sm[i]) {
                continue;
            }
            while (!tm[j]) {
                j++;
            }
            if (s.charAt(i) != t.charAt(j)) {
                transpositions++;
            }
            j++;
        }
        double m = matches;
        double jaro = (m / sl + m / tl + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(sl, tl)) && s.charAt(i) == t.charAt(i); i++) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
