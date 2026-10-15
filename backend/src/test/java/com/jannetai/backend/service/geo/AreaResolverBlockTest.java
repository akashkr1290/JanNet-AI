package com.jannetai.backend.service.geo;

import com.jannetai.backend.client.google.GoogleMapsClient;
import com.jannetai.backend.dto.geo.AreaMatchResponse;
import com.jannetai.backend.dto.geo.BlockRef;
import com.jannetai.backend.dto.geo.DistrictRef;
import com.jannetai.backend.dto.geo.LocalBodyRef;
import com.jannetai.backend.dto.geo.VillageRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V37: a pin gives the development block - from the village (LGD
 * villages_by_blocks), else from the Gram Panchayat; never in a town.
 */
@ExtendWith(MockitoExtension.class)
class AreaResolverBlockTest {

    @Mock private GeoRepository geo;
    @Mock private GoogleMapsClient google;
    private AreaResolver resolver;

    private static final LocalBodyRef PAKRI = new LocalBodyRef(95600, "Pakri", "RURAL", "Village Panchayat", 196);
    private static final LocalBodyRef TEKARI = new LocalBodyRef(900001, "Tekari", "URBAN", "Municipality", 196);
    private static final BlockRef GURUA_BLOCK = new BlockRef(1129, "Gurua", 196);
    private static final BlockRef OTHER_BLOCK = new BlockRef(1130, "Konch", 196);
    private static final VillageRef JOLAH = new VillageRef(255609, "Jolah Bigha", 196, "Gurua", "Gurua",
            List.of(PAKRI), null, null, 1129);
    private static final VillageRef NO_BLOCK = new VillageRef(255700, "Kharar", 196, "Gurua", null,
            List.of(PAKRI), null, null, null);

    @BeforeEach
    void setUp() {
        resolver = new AreaResolver(geo, google);
        lenient().when(geo.district(196)).thenReturn(Optional.of(new DistrictRef(196, "Gaya", 10, "Bihar", true)));
        lenient().when(geo.wardsOfBody(anyInt())).thenReturn(List.of());
        lenient().when(geo.wardBoundariesOfBody(anyInt())).thenReturn(List.of());
        lenient().when(geo.block(1129)).thenReturn(Optional.of(GURUA_BLOCK));
        lenient().when(geo.blockOfBody(95600)).thenReturn(Optional.of(OTHER_BLOCK));
    }

    private void pinInside(String layer, String ref) {
        when(geo.containing(24.9, 84.8)).thenReturn(List.of(new GeoRepository.BoundaryHit(layer, ref, "PUBLISHED")));
    }

    @Test
    void villageBoundaryGivesTheVillagesOwnBlock() {
        pinInside("VILLAGE", "255609");
        when(geo.village(255609)).thenReturn(Optional.of(JOLAH));

        AreaMatchResponse m = resolver.resolvePoint(24.9, 84.8);

        assertThat(m.village()).isEqualTo(JOLAH);
        assertThat(m.localBody()).isEqualTo(PAKRI);
        assertThat(m.block()).isEqualTo(GURUA_BLOCK); // the village's block, not the GP's (they can differ in LGD)
    }

    @Test
    void villageWithoutABlockFallsBackToItsGramPanchayatsBlock() {
        pinInside("VILLAGE", "255700");
        when(geo.village(255700)).thenReturn(Optional.of(NO_BLOCK));

        assertThat(resolver.resolvePoint(24.9, 84.8).block()).isEqualTo(OTHER_BLOCK);
    }

    @Test
    void gramPanchayatBoundaryAloneGivesItsBlock() {
        pinInside("PANCHAYAT", "95600");
        when(geo.localBody(95600)).thenReturn(Optional.of(PAKRI));

        AreaMatchResponse m = resolver.resolvePoint(24.9, 84.8);

        assertThat(m.village()).isNull();
        assertThat(m.block()).isEqualTo(OTHER_BLOCK);
    }

    @Test
    void aTownIsNotPartOfABlock() {
        pinInside("LOCAL_BODY", "900001");
        when(geo.localBody(900001)).thenReturn(Optional.of(TEKARI));

        AreaMatchResponse m = resolver.resolvePoint(24.9, 84.8);

        assertThat(m.localBody()).isEqualTo(TEKARI);
        assertThat(m.block()).isNull();
        verify(geo, never()).blockOfBody(anyInt());
    }

    @Test
    void blockOfIsEmptyWithoutVillageAndBody() {
        assertThat(AreaResolver.blockOf(geo, null, null)).isEmpty();
    }
}
