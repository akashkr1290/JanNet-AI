package com.jannetai.backend.service.complaint;

import com.jannetai.backend.client.google.GoogleMapsClient;
import com.jannetai.backend.dto.complaint.IncidentArea;
import com.jannetai.backend.dto.complaint.IncidentLocationRequest;
import com.jannetai.backend.dto.geo.BlockRef;
import com.jannetai.backend.dto.geo.DistrictRef;
import com.jannetai.backend.dto.geo.LocalBodyRef;
import com.jannetai.backend.dto.geo.VillageRef;
import com.jannetai.backend.entity.Location;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;

/** V37: the complaint's location keeps the development block of its village / Gram Panchayat. */
@ExtendWith(MockitoExtension.class)
class LocationServiceBlockTest {

    @Mock private LocationRepository locationRepository;
    @Mock private WardService wardService;
    @Mock private WardRepository wardRepository;
    @Mock private GeoRepository geo;
    @Mock private AreaResolver areaResolver;
    @Mock private GoogleMapsClient google;
    private LocationService service;

    private static final LocalBodyRef PAKRI = new LocalBodyRef(95600, "Pakri", "RURAL", "Village Panchayat", 196);
    private static final LocalBodyRef TEKARI = new LocalBodyRef(900001, "Tekari", "URBAN", "Municipality", 196);
    private static final VillageRef JOLAH = new VillageRef(255609, "Jolah Bigha", 196, "Gurua", "Gurua",
            List.of(PAKRI), null, null, 1129);

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
        lenient().when(geo.village(255609)).thenReturn(Optional.of(JOLAH));
        lenient().when(geo.district(196)).thenReturn(Optional.of(new DistrictRef(196, "Gaya", 10, "Bihar", true)));
        lenient().when(geo.block(1129)).thenReturn(Optional.of(new BlockRef(1129, "Gurua", 196)));
        lenient().when(geo.blockOfBody(95600)).thenReturn(Optional.of(new BlockRef(1130, "Konch", 196)));
        lenient().when(geo.wardsOfBody(anyInt())).thenReturn(List.of());
        lenient().when(areaResolver.findWard(anyInt(), anyDouble(), anyDouble(), any())).thenReturn(Optional.empty());
    }

    private Location create(Integer village, Integer body) {
        return service.resolveIncident(new IncidentLocationRequest(new BigDecimal("24.940000"), new BigDecimal("84.840000"),
                null, LocationSource.MANUAL_PIN, true, null, null, null, null, null,
                new IncidentArea(null, null, null, null, village, body)), null);
    }

    @Test
    void confirmedVillageStoresItsOwnBlock() {
        assertThat(create(255609, 95600).getBlockCode()).isEqualTo(1129);
    }

    @Test
    void gramPanchayatOnlyStoresTheGramPanchayatsBlock() {
        assertThat(create(null, 95600).getBlockCode()).isEqualTo(1130);
    }

    @Test
    void aTownHasNoBlock() {
        assertThat(create(null, 900001).getBlockCode()).isNull();
    }
}
