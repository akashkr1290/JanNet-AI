package com.jannetai.backend.service.assistant;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JanNet Sahayak's fixed sentences exist in all seven languages with the same placeholders. */
class AssistantTextsTest {

    @Test
    void everyTextHasAllLanguagesAndTheSamePlaceholders() {
        for (String key : AssistantTexts.keys()) {
            Map<String, String> byLang = AssistantTexts.allLanguages(key);
            assertThat(byLang.keySet()).as(key).containsExactlyInAnyOrderElementsOf(AssistantTexts.LANGUAGES);
            for (String lang : AssistantTexts.LANGUAGES) {
                assertThat(byLang.get(lang)).as(key + " " + lang).isNotBlank();
                for (int i = 0; i < 3; i++) {
                    assertThat(byLang.get(lang).contains("{" + i + "}")).as(key + " " + lang + " {" + i + "}")
                            .isEqualTo(byLang.get("EN").contains("{" + i + "}"));
                }
            }
        }
    }

    @Test
    void everyStatusAndCategoryHasALabel() {
        for (ComplaintStatus s : ComplaintStatus.values()) {
            for (String lang : AssistantTexts.LANGUAGES) {
                assertThat(AssistantTexts.statusLabel(s, lang)).isNotBlank();
                assertThat(AssistantTexts.statusExplanation(s, lang, "x")).isNotBlank();
            }
        }
        for (ComplaintCategory c : ComplaintCategory.values()) {
            assertThat(AssistantTexts.category(c, "TA")).isNotBlank();
        }
        assertThat(AssistantTexts.category(null, "EN")).isEqualTo("general");
    }

    @Test
    void statusLabelsAreTheAppLabels() {
        // flutter/lib/features/complaints/models/complaint_status.dart + translations_hi.dart
        assertThat(AssistantTexts.statusLabel(ComplaintStatus.AI_PROCESSING, "EN")).isEqualTo("Under Review");
        assertThat(AssistantTexts.statusLabel(ComplaintStatus.IN_PROGRESS, "EN")).isEqualTo("In Progress");
        assertThat(AssistantTexts.statusLabel(ComplaintStatus.IN_PROGRESS, "HI")).isEqualTo("प्रगति में");
        assertThat(AssistantTexts.statusLabel(ComplaintStatus.RESOLVED, "HI")).isEqualTo("सुलझाई गई");
    }

    @Test
    void placeholdersAndLanguageFallback() {
        assertThat(AssistantTexts.get(AssistantTexts.WITH_DEPARTMENT, "EN", "Public Works")).isEqualTo("It is with Public Works.");
        assertThat(AssistantTexts.get(AssistantTexts.THANKS, "fr")).isEqualTo(AssistantTexts.get(AssistantTexts.THANKS, "EN"));
        assertThat(AssistantTexts.language(" hi ")).isEqualTo("HI");
        assertThat(AssistantTexts.language(null)).isEqualTo("EN");
        assertThat(AssistantTexts.rejectionReason("SOMETHING_NEW", "EN")).isEqualTo("no reason was recorded");
        assertThatThrownBy(() -> AssistantTexts.get("no_such_key", "EN")).isInstanceOf(IllegalArgumentException.class);
    }
}
