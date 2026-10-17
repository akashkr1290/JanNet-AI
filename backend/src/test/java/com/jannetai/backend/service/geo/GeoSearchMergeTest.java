package com.jannetai.backend.service.geo;

import com.jannetai.backend.dto.geo.PlaceResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V37 map search order (GeoRepository#merge): a village is no longer cut off
 * when its name also matches many wards and post offices.
 */
class GeoSearchMergeTest {

    private static GeoRepository.Named n(String name, String kind) {
        return new GeoRepository.Named(name, new PlaceResult(name, null, 25, 85, kind, "DATASET"));
    }

    private static List<GeoRepository.Named> many(String prefix, String kind, int count) {
        List<GeoRepository.Named> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(n(prefix + " " + i, kind));
        }
        return out;
    }

    @Test
    void villagesStillShowWhenWardsAndPostOfficesFillTheList() {
        List<PlaceResult> r = GeoRepository.merge("rampur",
                many("Rampur Village", "VILLAGE", 10), many("Rampur Ward", "WARD", 10), many("Rampur BO", "POST_OFFICE", 10), 10);

        assertThat(r).hasSize(10);
        assertThat(r.stream().filter(p -> p.kind().equals("VILLAGE")).count()).isEqualTo(4);
        assertThat(r.get(0).kind()).isEqualTo("VILLAGE"); // villages take the first turn
    }

    @Test
    void exactNameMatchesComeFirst() {
        List<PlaceResult> r = GeoRepository.merge("rampur",
                List.of(n("Rampur Kalan", "VILLAGE"), n("Rampur", "VILLAGE")),
                List.of(n("Ward 3 - Rampur Road", "WARD")),
                List.of(n("Rampur", "POST_OFFICE")), 10);

        assertThat(r).extracting(PlaceResult::label).containsExactly("Rampur", "Rampur", "Rampur Kalan", "Ward 3 - Rampur Road");
        assertThat(r.get(0).kind()).isEqualTo("VILLAGE");
        assertThat(r.get(1).kind()).isEqualTo("POST_OFFICE");
    }

    @Test
    void shortListsAreKeptWhole() {
        List<PlaceResult> r = GeoRepository.merge("jawasa", List.of(n("Jawasa", "VILLAGE")), List.of(), List.of(), 10);

        assertThat(r).extracting(PlaceResult::label).containsExactly("Jawasa");
    }

    @Test
    void neverMoreThanTheLimit() {
        assertThat(GeoRepository.merge("x", many("X", "VILLAGE", 30), many("X", "WARD", 30), List.of(), 10)).hasSize(10);
        assertThat(GeoRepository.merge("x", List.of(), List.of(), List.of(), 10)).isEmpty();
    }
}
