package com.jannetai.backend.service.complaint;

import com.jannetai.backend.client.google.GoogleMapsClient;
import com.jannetai.backend.dto.complaint.IncidentArea;
import com.jannetai.backend.dto.complaint.IncidentLocationRequest;
import com.jannetai.backend.dto.geo.AreaMatchResponse;
import com.jannetai.backend.dto.geo.DistrictRef;
import com.jannetai.backend.dto.geo.LocalBodyRef;
import com.jannetai.backend.dto.geo.VillageRef;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.repository.LocationRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.WardService;
import com.jannetai.backend.service.geo.AreaResolver;
import com.jannetai.backend.service.geo.GeoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V34: the complaint's LGD area - checked when the app sends it, worked out when it does not. */
@ExtendWith(MockitoExtension.class)
class LocationServiceAreaTest {

    @Mock private LocationRepository locationRepository;
    @Mock private WardService wardService;
    @Mock private WardRepository wardRepository;
    @Mock private GeoRepository geo;
    @Mock private AreaResolver areaResolver;
    @Mock private GoogleMapsClient google;
    private LocationService service;

    private static final LocalBodyRef PAKRI = new LocalBodyRef(95600, "Pakri", "RURAL", "Village Panchayat", 196);
    private static final LocalBodyRef TEKARI = new LocalBodyRef(900001, "Tekari", "URBAN", "Municipality", 196);
    private static final VillageRef JOLAH_GURUA = new VillageRef(255609, "Jolah Bigha", 196, "Gurua", "Gurua", List.of(PAKRI), null, null);
    private static final DistrictRef GAYA = new DistrictRef(196, "Gaya", 10, "Bihar", true);
    private static final DistrictRef PATNA = new DistrictRef(188, "Patna", 10, "Bihar", false);
    private static final BigDecimal LAT = new BigDecimal("24.940000");
    private static final BigDecimal LNG = new BigDecimal("84.840000");

    @BeforeEach
    void setUp() {
        service = new LocationService(locationRepository, wardService, wardRepository, geo, areaResolver, google);
        ReflectionTestUtils.setField(service, "minLatitude", new BigDecimal("6.5"));
        ReflectionTestUtils.setField(service, "maxLatitude", new BigDecimal("37.1"));
        ReflectionTestUtils.setField(service, "minLongitude", new BigDecimal("68.0"));
        ReflectionTestUtils.setField(service, "maxLongitude", new BigDecimal("97.4"));
        lenient().when(locationRepository.save(any(Location.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(wardRepository.findByLocalBodyCodeIsNullAndIsActiveTrueOrderByNameAsc()).thenReturn(List.of());
        lenient().when(geo.localBody(95600)).thenReturn(Optional.of(PAKRI));
        lenient().when(geo.localBody(900001)).thenReturn(Optional.of(TEKARI));
        lenient().when(geo.village(255609)).thenReturn(Optional.of(JOLAH_GURUA));
        lenient().when(geo.district(196)).thenReturn(Optional.of(GAYA));
        lenient().when(geo.district(188)).thenReturn(Optional.of(PATNA));
        lenient().when(geo.anyDistrictServed()).thenReturn(true);
        lenient().when(areaResolver.findWard(org.mockito.ArgumentMatchers.anyInt(), anyDouble(), anyDouble(), any()))
                .thenReturn(Optional.empty());
    }

    private static IncidentLocationRequest pin(Long wardId, IncidentArea area) {
        return new IncidentLocationRequest(LAT, LNG, wardId, LocationSource.MANUAL_PIN, true, null, null, null, null, null, area);
    }

    @Test
    void confirmedVillageAndGramPanchayatAreStoredWithDistrictAndState() {
        Location l = service.resolveIncident(pin(null, new IncidentArea("824236", "Shiv Mandir", "Jolah Bigha",
                "Jolah Bigha, Gaya, Bihar 824236", 255609, 95600)), null);

        assertThat(l.getVillageCode()).isEqualTo(255609);
        assertThat(l.getLocalBodyCode()).isEqualTo(95600);
        assertThat(l.getDistrictCode()).isEqualTo(196);
        assertThat(l.getStateCode()).isEqualTo(10);
        assertThat(l.getPincode()).isEqualTo("824236");
        assertThat(l.getLandmark()).isEqualTo("Shiv Mandir");
        assertThat(l.getOutOfJurisdiction()).isFalse();
        verify(google, never()).reverseGeocode(anyDouble(), anyDouble()); // codes sent: no Google call
    }

    @Test
    void villageFromAnotherBodyIsRejected() {
        assertThatThrownBy(() -> service.resolveIncident(pin(null,
                new IncidentArea(null, null, null, null, 255609, 900001)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not part of");
    }

    @Test
    void wardOfAnotherBodyIsRejected() {
        Ward other = Ward.builder().wardId(9L).name("Ward 3").localBodyCode(900001).isActive(true).build();
        when(wardService.requireActiveWardEntity(9L)).thenReturn(other);

        assertThatThrownBy(() -> service.resolveIncident(pin(9L,
                new IncidentArea(null, null, null, null, 255609, 95600)), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wardChosenByTheCitizenIsMarkedCitizen() {
        Ward w = Ward.builder().wardId(7L).name("Ward 7").localBodyCode(95600).isActive(true).build();
        when(wardService.requireActiveWardEntity(7L)).thenReturn(w);

        Location l = service.resolveIncident(pin(7L, new IncidentArea(null, null, null, null, 255609, 95600)), null);

        assertThat(l.getWard()).isSameAs(w);
        assertThat(l.getWardMatch()).isEqualTo("CITIZEN");
    }

    @Test
    void districtNotServedIsOutOfJurisdiction() {
        LocalBodyRef danapur = new LocalBodyRef(777, "Danapur", "URBAN", "Municipal Council", 188);
        when(geo.localBody(777)).thenReturn(Optional.of(danapur));

        Location l = service.resolveIncident(pin(null, new IncidentArea(null, null, null, null, null, 777)), null);

        assertThat(l.getOutOfJurisdiction()).isTrue();
        assertThat(l.getFlags()).contains("OUT_OF_JURISDICTION");
    }

    private static AreaMatchResponse boundaryArea(LocalBodyRef body, VillageRef village, com.jannetai.backend.dto.geo.WardRef ward,
                                                  com.jannetai.backend.dto.geo.WardRef suggested, String pin) {
        return new AreaMatchResponse(null, GAYA, body, village, ward, ward != null ? "BOUNDARY" : null, List.of(), List.of(),
                List.of(), "EXACT", pin, "BOUNDARY", suggested, ward != null ? "VERIFIED_BOUNDARY" : null);
    }

    private static com.jannetai.backend.dto.geo.WardRef wardRef(long id, int body) {
        return new com.jannetai.backend.dto.geo.WardRef(id, "Ward " + id, String.valueOf(id), body, true, null, null, List.of());
    }

    @Test
    void olderAppWithoutCodesGetsTheAreaFromTheBoundaries() {
        when(areaResolver.resolvePoint(anyDouble(), anyDouble())).thenReturn(boundaryArea(PAKRI, JOLAH_GURUA, null, null, "824205"));

        Location l = service.resolveIncident(pin(null, IncidentArea.NONE), null);

        assertThat(l.getVillageCode()).isEqualTo(255609);
        assertThat(l.getLocalBodyCode()).isEqualTo(95600);
        assertThat(l.getPincode()).isEqualTo("824205");
        assertThat(l.getLocationVerificationStatus()).isEqualTo("VERIFIED_BOUNDARY"); // Pakri has no ward list
        verify(google, never()).reverseGeocode(anyDouble(), anyDouble());
    }

    @Test
    void wardInsideAPublishedWardMapIsAssignedAndVerified() {
        Ward w91 = Ward.builder().wardId(91L).name("Ward 91").localBodyCode(900001).isActive(true).build();
        when(areaResolver.resolvePoint(anyDouble(), anyDouble())).thenReturn(boundaryArea(TEKARI, null, wardRef(91, 900001), null, null));
        when(wardRepository.findById(91L)).thenReturn(Optional.of(w91));

        Location l = service.resolveIncident(pin(null, new IncidentArea(null, null, null, null, null, 900001)), null);

        assertThat(l.getWard()).isSameAs(w91);
        assertThat(l.getWardMatch()).isEqualTo("BOUNDARY");
        assertThat(l.getLocationVerificationStatus()).isEqualTo("VERIFIED_BOUNDARY");
        assertThat(l.getVerificationReason()).isNull();
    }

    @Test
    void withoutAWardMapNoWardIsGuessedAndStaffMustVerify() {
        Ward near = Ward.builder().wardId(12L).name("Ward 12").localBodyCode(900001).isActive(true).build();
        when(areaResolver.resolvePoint(anyDouble(), anyDouble())).thenReturn(boundaryArea(TEKARI, null, null, wardRef(12, 900001), null));
        when(geo.wardsOfBody(900001)).thenReturn(List.of(wardRef(12, 900001), wardRef(13, 900001)));
        when(wardRepository.findById(12L)).thenReturn(Optional.of(near));

        Location l = service.resolveIncident(pin(null, new IncidentArea(null, null, null, null, null, 900001)), null);

        assertThat(l.getWard()).isNull();
        assertThat(l.getSuggestedWard()).isSameAs(near);
        assertThat(l.getLocationVerificationStatus()).isEqualTo("REQUIRES_VERIFICATION");
        assertThat(l.getVerificationReason()).isEqualTo("NO_WARD_BOUNDARY");
    }

    @Test
    void wardChosenAgainstTheWardMapIsKeptButFlagged() {
        Ward chosen = Ward.builder().wardId(7L).name("Ward 7").localBodyCode(900001).isActive(true).build();
        Ward mapped = Ward.builder().wardId(8L).name("Ward 8").localBodyCode(900001).isActive(true).build();
        when(wardService.requireActiveWardEntity(7L)).thenReturn(chosen);
        when(areaResolver.resolvePoint(anyDouble(), anyDouble())).thenReturn(boundaryArea(TEKARI, null, wardRef(8, 900001), null, null));
        when(wardRepository.findById(8L)).thenReturn(Optional.of(mapped));

        Location l = service.resolveIncident(pin(7L, new IncidentArea(null, null, null, null, null, 900001)), null);

        assertThat(l.getWard()).isSameAs(chosen);
        assertThat(l.getWardMatch()).isEqualTo("CITIZEN");
        assertThat(l.getSuggestedWard()).isSameAs(mapped);
        assertThat(l.getVerificationReason()).isEqualTo("WARD_DIFFERS_FROM_MAP");
    }

    @Test
    void roughGpsFixNeedsVerificationEvenInsideAWardMap() {
        Ward w91 = Ward.builder().wardId(91L).name("Ward 91").localBodyCode(900001).isActive(true).build();
        when(areaResolver.resolvePoint(anyDouble(), anyDouble())).thenReturn(boundaryArea(TEKARI, null, wardRef(91, 900001), null, null));
        when(wardRepository.findById(91L)).thenReturn(Optional.of(w91));
        ReflectionTestUtils.setField(service, "lowAccuracyMeters", 50.0);

        Location l = service.resolveIncident(new IncidentLocationRequest(LAT, LNG, null, LocationSource.CAPTURE_GPS, true,
                180.0, null, null, null, null, IncidentArea.NONE), null);

        assertThat(l.getWard()).isSameAs(w91);
        assertThat(l.getLocationVerificationStatus()).isEqualTo("REQUIRES_VERIFICATION");
        assertThat(l.getVerificationReason()).isEqualTo("LOW_GPS_ACCURACY");
    }

    @Test
    void staffVerificationSetsTheWardAndClearsTheFlag() {
        Ward w = Ward.builder().wardId(12L).name("Ward 12").localBodyCode(900001).isActive(true).build();
        when(wardService.requireActiveWardEntity(12L)).thenReturn(w);
        Location l = Location.builder().latitude(LAT).longitude(LNG).source(LocationSource.MANUAL_PIN)
                .locationVerificationStatus("REQUIRES_VERIFICATION").verificationReason("NO_WARD_BOUNDARY").build();

        service.verifyByStaff(l, 12L);

        assertThat(l.getWard()).isSameAs(w);
        assertThat(l.getWardMatch()).isEqualTo("STAFF");
        assertThat(l.getLocationVerificationStatus()).isEqualTo("STAFF_VERIFIED");
        assertThat(l.getVerificationReason()).isNull();
    }

    @Test
    void invalidPincodeIsRejected() {
        assertThatThrownBy(() -> service.resolveIncident(pin(null, new IncidentArea("12345", null, null, null, null, null)), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
