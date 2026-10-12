package com.jannetai.backend.service.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PersonalSettingKeyTest {

    @Test
    void languageAcceptsEverySupportedAppLanguage() {
        for (String code : new String[] {"EN", "HI", "MR", "BN", "TA", "TE", "KN"}) {
            assertDoesNotThrow(() -> PersonalSettingKey.LANGUAGE.validate(code), code);
        }
    }

    @Test
    void languageRejectsUnsupportedValues() {
        assertThrows(IllegalArgumentException.class, () -> PersonalSettingKey.LANGUAGE.validate("FR"));
        assertThrows(IllegalArgumentException.class, () -> PersonalSettingKey.LANGUAGE.validate("hi"));
        assertThrows(IllegalArgumentException.class, () -> PersonalSettingKey.LANGUAGE.validate(null));
    }
}
