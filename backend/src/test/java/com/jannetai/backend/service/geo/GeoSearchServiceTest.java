package com.jannetai.backend.service.geo;

import com.jannetai.backend.client.osm.NominatimClient;
import com.jannetai.backend.client.osm.PhotonClient;
import com.jannetai.backend.dto.geo.PlaceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GeoSearchService} - the key-free search box: own dataset first,
 * OpenStreetMap only for landmarks, never when our data already answers.
 */
@ExtendWith(MockitoExtension.class)
class GeoSearchServiceTest {

    @Mock private GeoRepository geo;
    @Mock private NominatimClient osm;
    @Mock private PhotonClient photon;

    private GeoSearchService service;

    @BeforeEach
    void setUp() {
        service = new GeoSearchService(geo, osm);
    }

    private static PlaceResult ward(String label, double lat, double lng) {
        return new PlaceResult(label, "Ghaziabad", lat, lng, "WARD", "DATASET");
    }

    @Test
    void tooShortTextAnswersNothingAndAsksNobody() {
        assertThat(service.search(" a ", null, null)).isEmpty();
        assertThat(service.search(null, null, null)).isEmpty();
        verifyNoInteractions(geo, osm);
    }

    @Test
    void tooLongTextIsRejected() {
        assertThatThrownBy(() -> service.search("x".repeat(121), null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPinCodeAnswersWithThePinCentreFirst() {
        when(geo.pincodeCentre("201002")).thenReturn(Optional.of(new double[]{28.6692, 77.4538}));
        when(geo.postOffices("201002")).thenReturn(List.of(
                new GeoRepository.PostOffice("Kavi Nagar S.O", 145, new BigDecimal("28.66"), new BigDecimal("77.45"))));
        when(geo.searchPlaces("201002", GeoSearchService.LIMIT)).thenReturn(List.of());
        when(osm.isEnabled()).thenReturn(true);

        List<PlaceResult> out = service.search("201002", null, null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).label()).isEqualTo("PIN 201002");
        assertThat(out.get(0).kind()).isEqualTo("PINCODE");
        assertThat(out.get(0).secondary()).isEqualTo("Kavi Nagar"); // post office suffix "S.O" removed
        assertThat(out.get(0).source()).isEqualTo("DATASET");
        verify(osm, never()).search(anyString(), any(), any()); // digits are never sent to OpenStreetMap
    }

    @Test
    void aPinWithoutAPolygonUsesTheAverageOfItsPostOffices() {
        when(geo.pincodeCentre("824231")).thenReturn(Optional.empty());
        when(geo.postOffices("824231")).thenReturn(List.of(
                new GeoRepository.PostOffice("Bodhgaya S.O", 196, new BigDecimal("24.60"), new BigDecimal("84.00")),
                new GeoRepository.PostOffice("Miya Bigha B.O", 196, new BigDecimal("24.80"), new BigDecimal("84.20")),
                new GeoRepository.PostOffice("No Position B.O", 196, null, null)));
        when(geo.searchPlaces("824231", GeoSearchService.LIMIT)).thenReturn(List.of());
        when(osm.isEnabled()).thenReturn(true);

        PlaceResult pin = service.search("824231", null, null).get(0);

        assertThat(pin.latitude()).isEqualTo(24.70, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(pin.longitude()).isEqualTo(84.10, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aPinWeDoNotKnowIsNotInvented() {
        when(geo.pincodeCentre("999999")).thenReturn(Optional.empty());
        when(geo.postOffices("999999")).thenReturn(List.of());
        when(geo.searchPlaces("999999", GeoSearchService.LIMIT)).thenReturn(List.of());
        when(osm.isEnabled()).thenReturn(true);

        assertThat(service.search("999999", null, null)).isEmpty();
    }

    @Test
    void openStreetMapIsAskedOnlyWhenOurDataHasFewResults() {
        when(geo.searchPlaces("Republic Crossing", GeoSearchService.LIMIT)).thenReturn(List.of());
        when(osm.isEnabled()).thenReturn(true);
        when(osm.search("Republic Crossing", 28.7, 77.4)).thenReturn(List.of(
                new NominatimClient.OsmPlace("Republic Crossing", "Republic Crossing, Ghaziabad", 28.7001, 77.4302)));

        List<PlaceResult> out = service.search("Republic Crossing", 28.7, 77.4);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).source()).isEqualTo("OSM");
        assertThat(out.get(0).kind()).isEqualTo("PLACE");
    }

    @Test
    void openStreetMapIsNotAskedWhenOurDataAlreadyAnswers() {
        when(geo.searchPlaces("Raj Nagar", GeoSearchService.LIMIT)).thenReturn(List.of(
                ward("Ward 84 - Raj Nagar", 28.68, 77.44), ward("Ward 85 - Raj Nagar Ext", 28.69, 77.43),
                ward("Ward 86 - Raj Nagar II", 28.70, 77.42), ward("Ward 87 - Raj Nagar III", 28.71, 77.41)));

        List<PlaceResult> out = service.search("Raj Nagar", null, null);

        assertThat(out).hasSize(4);
        verifyNoInteractions(osm);
    }

    @Test
    void ourOwnResultsComeBeforeOpenStreetMapAndDuplicatesAreDropped() {
        PlaceResult own = ward("Ward 84 - Raj Nagar", 28.6823, 77.4475);
        when(geo.searchPlaces("Raj Nagar", GeoSearchService.LIMIT)).thenReturn(List.of(own, own));
        when(osm.isEnabled()).thenReturn(true);
        when(osm.search(anyString(), any(), any())).thenReturn(List.of(
                new NominatimClient.OsmPlace("Raj Nagar", "Raj Nagar, Ghaziabad", 28.6830, 77.4480)));

        List<PlaceResult> out = service.search("Raj Nagar", null, null);

        assertThat(out.get(0).source()).isEqualTo("DATASET");
        assertThat(out).hasSize(2); // the doubled ward is kept once
    }

    @Test
    void aSwitchedOffOpenStreetMapLeavesOnlyTheDataset() {
        when(geo.searchPlaces("Indirapuram", GeoSearchService.LIMIT)).thenReturn(List.of());
        when(osm.isEnabled()).thenReturn(false);

        assertThat(service.search("Indirapuram", null, null)).isEmpty();
        verify(osm, never()).search(anyString(), any(), any());
        verify(geo, never()).pincodeCentre(anyString());
        verify(geo, never()).postOffices(anyString());
    }

    @Test
    void neverReturnsMoreThanTheLimit() {
        List<PlaceResult> many = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(ward("Ward " + i, 28.0 + i, 77.0 + i));
        }
        when(geo.searchPlaces(anyString(), anyInt())).thenReturn(many);

        assertThat(service.search("Ward", null, null)).hasSize(GeoSearchService.LIMIT);
    }

    @Test
    void photonIsAskedFirstAndNominatimNotNeededWhenItAnswers() {
        service.setPhoton(photon);
        when(geo.searchPlaces("Republic Crossing", GeoSearchService.LIMIT)).thenReturn(List.of());
        when(photon.isEnabled()).thenReturn(true);
        when(photon.search("Republic Crossing", 28.7, 77.4)).thenReturn(List.of(
                new NominatimClient.OsmPlace("Crossings Republik", "Ghaziabad, Uttar Pradesh", 28.7001, 77.4302)));

        List<PlaceResult> out = service.search("Republic Crossing", 28.7, 77.4);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).label()).isEqualTo("Crossings Republik");
        assertThat(out.get(0).source()).isEqualTo("OSM");
        verifyNoInteractions(osm);
    }

    @Test
    void nominatimIsTheFallbackWhenPhotonFindsNothing() {
        service.setPhoton(photon);
        when(geo.searchPlaces("Connaught Place", GeoSearchService.LIMIT)).thenReturn(List.of());
        when(photon.isEnabled()).thenReturn(true);
        when(photon.search(anyString(), any(), any())).thenReturn(List.of());
        when(osm.isEnabled()).thenReturn(true);
        when(osm.search("Connaught Place", null, null)).thenReturn(List.of(
                new NominatimClient.OsmPlace("Connaught Place", "New Delhi", 28.6315, 77.2167)));

        List<PlaceResult> out = service.search("Connaught Place", null, null);

        assertThat(out).extracting(PlaceResult::label).containsExactly("Connaught Place");
    }

    @Test
    void aSwitchedOffPhotonFallsBackToNominatim() {
        service.setPhoton(photon);
        when(geo.searchPlaces("Indirapuram", GeoSearchService.LIMIT)).thenReturn(List.of());
        when(photon.isEnabled()).thenReturn(false);
        when(osm.isEnabled()).thenReturn(true);
        when(osm.search("Indirapuram", null, null)).thenReturn(List.of(
                new NominatimClient.OsmPlace("Indirapuram", "Ghaziabad", 28.64, 77.37)));

        assertThat(service.search("Indirapuram", null, null)).hasSize(1);
        verify(photon, never()).search(anyString(), any(), any());
    }
}
