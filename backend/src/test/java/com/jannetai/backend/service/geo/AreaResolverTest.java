package com.jannetai.backend.service.geo;

import com.jannetai.backend.client.google.GoogleAddress;
import com.jannetai.backend.client.google.GoogleMapsClient;
import com.jannetai.backend.dto.geo.AreaMatchResponse;
import com.jannetai.backend.dto.geo.AreaRef;
import com.jannetai.backend.dto.geo.DistrictRef;
import com.jannetai.backend.dto.geo.LocalBodyRef;
import com.jannetai.backend.dto.geo.VillageRef;
import com.jannetai.backend.dto.geo.WardRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V34: Google address -> LGD village / body / ward. Fixture names and codes
 * are real LGD rows from the V35 seed (Gaya 196, Ghaziabad 145).
 */
@ExtendWith(MockitoExtension.class)
class AreaResolverTest {

    @Mock private GeoRepository geo;
    @Mock private GoogleMapsClient google;
    private AreaResolver resolver;

    private static final LocalBodyRef PAKRI = new LocalBodyRef(95600, "Pakri", "RURAL", "Village Panchayat", 196);
    private static final LocalBodyRef TEKARI = new LocalBodyRef(900001, "Tekari", "URBAN", "Municipality", 196);
    private static final LocalBodyRef GHAZIABAD = new LocalBodyRef(248988, "Ghaziabad", "URBAN", "Municipal Corporation", 145);
    private static final VillageRef JOLAH_GURUA = new VillageRef(255609, "Jolah Bigha", 196, "Gurua", "Gurua", List.of(PAKRI), null, null);
    private static final VillageRef JOLAH_TIKARI = new VillageRef(254810, "Jolah Bigha", 196, "Tikari", "Tekari", List.of(TEKARI), null, null);
    private static final VillageRef GURUA = new VillageRef(255600, "Gurua", 196, "Gurua", "Gurua", List.of(), null, null);
    private static final String SQUARE = "{\"type\":\"Polygon\",\"coordinates\":[[[77.40,28.68],[77.45,28.68],[77.45,28.72],[77.40,28.72],[77.40,28.68]]]}";

    @BeforeEach
    void setUp() {
        resolver = new AreaResolver(geo, google);
        lenient().when(geo.states()).thenReturn(List.of(new AreaRef(10, "Bihar"), new AreaRef(9, "Uttar Pradesh")));
        lenient().when(geo.districts(10)).thenReturn(List.of(new DistrictRef(196, "Gaya", 10, "Bihar", true),
                new DistrictRef(188, "Patna", 10, "Bihar", false)));
        lenient().when(geo.districts(9)).thenReturn(List.of(new DistrictRef(145, "Ghaziabad", 9, "Uttar Pradesh", true)));
        lenient().when(geo.district(196)).thenReturn(Optional.of(new DistrictRef(196, "Gaya", 10, "Bihar", true)));
        lenient().when(geo.villagesOfDistrict(196)).thenReturn(List.of(GURUA, JOLAH_GURUA, JOLAH_TIKARI));
        lenient().when(geo.villagesOfDistrict(145)).thenReturn(List.of());
        lenient().when(geo.villagesOfDistrict(188)).thenReturn(List.of());
        lenient().when(geo.localBodies(196, "URBAN")).thenReturn(List.of(TEKARI));
        lenient().when(geo.localBodies(145, "URBAN")).thenReturn(List.of(GHAZIABAD));
        lenient().when(geo.villageCodesForPincode(anyString())).thenReturn(List.of());
        lenient().when(geo.wardsOfBody(anyInt())).thenReturn(List.of());
        lenient().when(geo.wardBoundariesOfBody(anyInt())).thenReturn(List.of());
        lenient().when(geo.wardLocalityKeys(anyInt())).thenReturn(Map.of());
        lenient().when(geo.nearestPostOffice(anyDouble(), anyDouble())).thenReturn(Optional.empty());
    }

    private static GoogleAddress address(String state, List<String> districts, List<String> localities) {
        return new GoogleAddress("x", 0, 0, null, state, districts, localities, null, null);
    }

    @Test
    void sameNamedVillagesWithoutContextAreOfferedAsChoices() {
        AreaMatchResponse m = resolver.resolve(24.9, 84.8, address("Bihar", List.of("Gaya"), List.of("Jolah Bigha")));

        assertThat(m.village()).isNull();
        assertThat(m.confidence()).isEqualTo("CHOOSE");
        assertThat(m.villageChoices()).contains(JOLAH_GURUA, JOLAH_TIKARI);
    }

    @Test
    void tehsilNameSinglesOutTheVillageAndItsGramPanchayat() {
        AreaMatchResponse m = resolver.resolve(24.9, 84.8, address("Bihar", List.of("Gaya"), List.of("Jolah Bigha", "Gurua")));

        assertThat(m.village()).isEqualTo(JOLAH_GURUA);
        assertThat(m.localBody()).isEqualTo(PAKRI);
        assertThat(m.confidence()).isEqualTo("EXACT");
    }

    @Test
    void soundAlikeSpellingIsALikelyMatch() {
        AreaMatchResponse m = resolver.resolve(24.9, 84.8, address("Bihar", List.of("Gaya"), List.of("Jolhabigha", "Tikari")));

        assertThat(m.village()).isEqualTo(JOLAH_TIKARI);
        assertThat(m.localBody()).isEqualTo(TEKARI);
        assertThat(m.confidence()).isEqualTo("LIKELY");
    }

    @Test
    void cityColonyGivesTheMunicipalBodyAndTheWardByBoundary() {
        WardRef w12 = new WardRef(501L, "Ward 12", "12", 248988, true, null, null, List.of());
        lenient().when(geo.wardsOfBody(248988)).thenReturn(List.of(w12));
        lenient().when(geo.wardBoundariesOfBody(248988)).thenReturn(List.of(new GeoRepository.WardBoundary(501L, SQUARE)));

        AreaMatchResponse m = resolver.resolve(28.70, 77.42,
                address("Uttar Pradesh", List.of("Ghaziabad", "Meerut Division"), List.of("Raj Nagar Extension", "Ghaziabad")));

        assertThat(m.localBody()).isEqualTo(GHAZIABAD);
        assertThat(m.village()).isNull();
        assertThat(m.ward()).isEqualTo(w12);
        assertThat(m.wardMatch()).isEqualTo("BOUNDARY");
    }

    @Test
    void listedColonyNameOnlySuggestsTheWardWhenThereIsNoBoundary() {
        // spec 2026-10-11: never assign a ward from an address / name match
        WardRef w13 = new WardRef(502L, "Ward 13", "13", 248988, false, null, null, List.of("Morta"));
        lenient().when(geo.wardsOfBody(248988)).thenReturn(List.of(w13));
        lenient().when(geo.wardLocalityKeys(248988)).thenReturn(Map.of("morta", List.of(502L)));

        AreaMatchResponse m = resolver.resolve(28.80, 77.60, address("Uttar Pradesh", List.of("Ghaziabad"), List.of("Morta", "Ghaziabad")));

        assertThat(m.ward()).isNull();
        assertThat(m.wardMatch()).isNull();
        assertThat(m.suggestedWard()).isEqualTo(w13);
        assertThat(m.wardVerification()).isEqualTo("REQUIRES_VERIFICATION");
        assertThat(m.areaSource()).isEqualTo("NAME_MATCH");
    }

    @Test
    void publishedBoundariesDecideTheAreaWardAndPin() {
        WardRef w91 = new WardRef(51897L, "Ward 91 - Kavi Nagar", "91", 248988, true, null, null, List.of(), "SBM", "PUBLISHED");
        when(geo.containing(28.6692, 77.4538)).thenReturn(List.of(
                new GeoRepository.BoundaryHit("WARD", "51897", "PUBLISHED"),
                new GeoRepository.BoundaryHit("LOCAL_BODY", "248988", "PUBLISHED"),
                new GeoRepository.BoundaryHit("PINCODE", "201002", "PUBLISHED")));
        when(geo.activeWard(51897L)).thenReturn(Optional.of(w91));
        when(geo.localBody(248988)).thenReturn(Optional.of(GHAZIABAD));
        when(geo.district(145)).thenReturn(Optional.of(new DistrictRef(145, "Ghaziabad", 9, "Uttar Pradesh", true)));
        lenient().when(geo.wardsOfBody(248988)).thenReturn(List.of(w91));

        AreaMatchResponse m = resolver.resolve(28.6692, 77.4538, null);

        assertThat(m.localBody()).isEqualTo(GHAZIABAD);
        assertThat(m.ward()).isEqualTo(w91);
        assertThat(m.wardMatch()).isEqualTo("BOUNDARY");
        assertThat(m.pincode()).isEqualTo("201002");
        assertThat(m.areaSource()).isEqualTo("BOUNDARY");
        assertThat(m.wardVerification()).isEqualTo("VERIFIED_BOUNDARY");
        verify(google, never()).reverseGeocode(anyDouble(), anyDouble());
    }

    @Test
    void unverifiedWardMapIsNotUsedAndTheCitizenChooses() {
        WardRef w5 = new WardRef(9005L, "Ward 5", "5", 274820, true, null, null, List.of(), "SBM", "UNVERIFIED");
        LocalBodyRef khoda = new LocalBodyRef(274820, "Khoda Makanpur", "URBAN", "Municipality", 145);
        when(geo.containing(28.62, 77.35)).thenReturn(List.of(
                new GeoRepository.BoundaryHit("WARD", "9005", "UNVERIFIED"),
                new GeoRepository.BoundaryHit("LOCAL_BODY", "274820", "PUBLISHED")));
        when(geo.localBody(274820)).thenReturn(Optional.of(khoda));
        when(geo.district(145)).thenReturn(Optional.of(new DistrictRef(145, "Ghaziabad", 9, "Uttar Pradesh", true)));
        lenient().when(geo.wardsOfBody(274820)).thenReturn(List.of(w5));

        AreaMatchResponse m = resolver.resolve(28.62, 77.35, null);

        assertThat(m.localBody()).isEqualTo(khoda);
        assertThat(m.ward()).isNull();
        assertThat(m.wardVerification()).isEqualTo("REQUIRES_VERIFICATION");
        assertThat(m.wardChoices()).containsExactly(w5);
    }

    @Test
    void twoOverlappingWardMapsAreAmbiguousSoNoWardIsSet() {
        when(geo.containing(28.6, 77.4)).thenReturn(List.of(
                new GeoRepository.BoundaryHit("WARD", "1", "PUBLISHED"),
                new GeoRepository.BoundaryHit("WARD", "2", "PUBLISHED"),
                new GeoRepository.BoundaryHit("LOCAL_BODY", "248988", "PUBLISHED")));
        when(geo.localBody(248988)).thenReturn(Optional.of(GHAZIABAD));
        when(geo.district(145)).thenReturn(Optional.of(new DistrictRef(145, "Ghaziabad", 9, "Uttar Pradesh", true)));

        AreaMatchResponse m = resolver.resolve(28.6, 77.4, null);

        assertThat(m.localBody()).isEqualTo(GHAZIABAD);
        assertThat(m.ward()).isNull();
        verify(geo, never()).activeWard(anyLong());
    }

    @Test
    void onlyAPinPolygonIsNotEnoughForAnArea() {
        when(geo.containing(25.0, 85.0)).thenReturn(List.of(new GeoRepository.BoundaryHit("PINCODE", "824231", "PUBLISHED")));

        AreaMatchResponse m = resolver.resolve(25.0, 85.0, null);

        assertThat(m.localBody()).isNull();
        assertThat(m.areaSource()).isNotEqualTo("BOUNDARY");
    }

    @Test
    void addressLineIsBuiltFromOurOwnAreaNames() {
        AreaMatchResponse area = new AreaMatchResponse(new AreaRef(9, "Uttar Pradesh"),
                new DistrictRef(145, "Ghaziabad", 9, "Uttar Pradesh", true), GHAZIABAD, null,
                new WardRef(1L, "Ward 91 - Kavi Nagar", "91", 248988, true, null, null, List.of()), "BOUNDARY",
                List.of(), List.of(), List.of(), "EXACT", "201002", "BOUNDARY", null, "VERIFIED_BOUNDARY");

        assertThat(AreaResolver.addressLine(List.of("Ambedkar Road"), area))
                .isEqualTo("Ambedkar Road, Ward 91 - Kavi Nagar, Ghaziabad, Ghaziabad, Uttar Pradesh 201002");
    }

    @Test
    void unservedDistrictIsReportedSoTheAppCanWarn() {
        AreaMatchResponse m = resolver.resolve(25.6, 85.1, address("Bihar", List.of("Patna"), List.of("Danapur")));

        assertThat(m.district().name()).isEqualTo("Patna");
        assertThat(m.district().served()).isFalse();
        assertThat(m.confidence()).isEqualTo("NONE");
    }

    @Test
    void withoutGoogleTheNearestPostOfficeGivesTheDistrict() {
        lenient().when(geo.nearestPostOffice(anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new GeoRepository.NearOffice("824236", "Tekari S.O", 196)));

        AreaMatchResponse m = resolver.resolve(24.94, 84.84, null);

        assertThat(m.district().code()).isEqualTo(196);
        assertThat(m.localBody()).isNull();
        assertThat(m.areaSource()).isEqualTo("POST_OFFICE");
        assertThat(m.pincode()).isNull(); // the office's PIN may not be the point's PIN
    }
}
