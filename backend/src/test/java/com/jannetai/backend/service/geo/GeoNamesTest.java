package com.jannetai.backend.service.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Google / citizen spellings vs LGD spellings of the same place (V34 location upgrade). */
class GeoNamesTest {

    @Test
    void normalizeMatchesTheImporterKey() {
        // database/tools/lgd_import/lgd_import.py name_key() gives the same values
        assertThat(GeoNames.normalize("Jolah Bigha")).isEqualTo("jolahbigha");
        assertThat(GeoNames.normalize("Raj Nagar Extn.")).isEqualTo("rajnagarextn");
        assertThat(GeoNames.normalize(null)).isEmpty();
    }

    @Test
    void soundAlikeSpellingsAreStrongMatches() {
        assertThat(GeoNames.similarity("Jolhabigha", "Jolah Bigha")).isGreaterThanOrEqualTo(GeoNames.STRONG);
        assertThat(GeoNames.similarity("Tikari", "Tekari")).isGreaterThanOrEqualTo(GeoNames.STRONG);
        assertThat(GeoNames.similarity("Gaziabad", "Ghaziabad")).isGreaterThanOrEqualTo(GeoNames.STRONG);
        assertThat(GeoNames.similarity("Bela Ganj", "Belaganj")).isEqualTo(1.0);
    }

    @Test
    void differentPlacesAreNotStrongMatches() {
        assertThat(GeoNames.similarity("Gurua", "Guraru")).isLessThan(GeoNames.STRONG);
        assertThat(GeoNames.similarity("Patna", "Gaya")).isLessThan(GeoNames.WEAK);
    }
}
