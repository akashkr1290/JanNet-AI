package com.jannetai.backend.dto.geo;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/v1/geo/pincodes/{pin}: the manual flow's first step ("enter your
 * PIN"). The map opens on {@code centreLatitude/Longitude} with the pin
 * there, and the citizen picks a village or town from the lists.
 *
 * @param villagesExact true when {@code villages} come from the PIN -> village
 *                      list (village_pincodes); false when that list is not
 *                      loaded and the villages of the PIN's district/tehsil
 *                      are offered instead (the app then shows a search box first)
 * @param postOffices   India Post office names for the PIN (often village names)
 * @param localBodies   V36: every Gram Panchayat / urban body LGD links to the
 *                      PIN (pincode_local_bodies) - a PIN often covers several
 */
public record PincodeAreaResponse(
        String pincode,
        DistrictRef district,
        BigDecimal centreLatitude,
        BigDecimal centreLongitude,
        boolean villagesExact,
        List<VillageRef> villages,
        List<LocalBodyRef> urbanBodies,
        List<String> postOffices,
        List<PincodeBodyRef> localBodies
) {
    public PincodeAreaResponse(String pincode, DistrictRef district, BigDecimal centreLatitude, BigDecimal centreLongitude,
                               boolean villagesExact, List<VillageRef> villages, List<LocalBodyRef> urbanBodies,
                               List<String> postOffices) {
        this(pincode, district, centreLatitude, centreLongitude, villagesExact, villages, urbanBodies, postOffices, List.of());
    }
}
