package com.jannetai.backend.service.assistant;

import com.jannetai.backend.entity.HelpArticle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real seeded help articles, read from the V38 migration on the test
 * classpath (pom.xml copies database/migrations/ to db/migration), so the
 * assistant tests run against exactly what production answers from.
 */
final class HelpArticleFixtures {

    private static final String S = "'((?:[^']|'')*)'";
    private static final Pattern ROW = Pattern.compile(
            "\\(" + S + ", " + S + ", " + S + ", " + S + ", " + S + ", " + S + ", (\\d+)\\)");
    private static List<HelpArticle> cache;

    private HelpArticleFixtures() {
    }

    static synchronized List<HelpArticle> all() {
        if (cache == null) {
            try (InputStream in = HelpArticleFixtures.class.getClassLoader()
                    .getResourceAsStream("db/migration/V38__assistant_chatbot.sql")) {
                if (in == null) {
                    throw new IllegalStateException("V38__assistant_chatbot.sql is not on the test classpath");
                }
                String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                List<HelpArticle> rows = new ArrayList<>();
                Matcher m = ROW.matcher(sql.substring(sql.indexOf("INSERT INTO help_articles")));
                long id = 1;
                while (m.find()) {
                    rows.add(HelpArticle.builder().articleId(id++).slug(un(m.group(1))).language(un(m.group(2)))
                            .topic(un(m.group(3))).title(un(m.group(4))).body(un(m.group(5))).keywords(un(m.group(6)))
                            .sortOrder(Integer.parseInt(m.group(7))).isActive(true).build());
                }
                cache = List.copyOf(rows);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return cache;
    }

    /** What HelpArticleRepository.findByLanguageInAndIsActiveTrueOrderBySortOrderAsc returns. */
    static List<HelpArticle> inLanguages(Collection<String> languages) {
        return all().stream().filter(a -> languages.contains(a.getLanguage())).toList();
    }

    private static String un(String sqlString) {
        return sqlString.replace("''", "'").replace("\\\\", "\\");
    }
}
