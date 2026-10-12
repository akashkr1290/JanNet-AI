package com.jannetai.backend.dto.geo;

import java.util.List;

/**
 * What service/geo/AreaResolver worked out for a point or PIN: the LGD
 * state / district / local body / village / ward, plus the choices the app
 * shows when it is not sure.
 *
 * <p>The app (flutter .../location/area_confirm_card.dart) asks "Is this
 * place {village or locality}, {localBody}?" when {@code confidence} is
 * EXACT or LIKELY, and shows {@code villageChoices} / {@code wardChoices}
 * as one-tap lists otherwise.
 *
 * @param confidence EXACT (boundary or exact name), LIKELY (sound-alike match),
 *                   CHOOSE (only choices), NONE (outside the loaded data)
 * @param wardMatch  how {@code ward} was found: BOUNDARY (the point lies inside
 *                   a published ward map) - the only way a ward is set
 *                   automatically (spec 2026-10-11); null when the citizen must
 *                   choose from {@code wardChoices}
 * @param pincode    V36: PIN whose published boundary contains the point, or null
 * @param areaSource V36: BOUNDARY (polygons of geo_boundaries), NAME_MATCH
 *                   (Google address names), POST_OFFICE (district of the nearest
 *                   post office only) or NONE
 * @param suggestedWard V36: a likely ward when none is proven (listed colony
 *                   name or nearest ward centre) - a hint, never assigned
 * @param wardVerification V36: VERIFIED_BOUNDARY when {@code ward} was found by
 *                   boundary; REQUIRES_VERIFICATION when the body has wards but
 *                   none was proven; null when the body has no ward list
 */
public record AreaMatchResponse(
        AreaRef state,
        DistrictRef district,
        LocalBodyRef localBody,
        VillageRef village,
        WardRef ward,
        String wardMatch,
        List<WardRef> wardChoices,
        List<VillageRef> villageChoices,
        List<LocalBodyRef> localBodyChoices,
        String confidence,
        String pincode,
        String areaSource,
        WardRef suggestedWard,
        String wardVerification
) {
    /** Pre-V36 shape: an area worked out from names. */
    public AreaMatchResponse(AreaRef state, DistrictRef district, LocalBodyRef localBody, VillageRef village, WardRef ward,
                             String wardMatch, List<WardRef> wardChoices, List<VillageRef> villageChoices,
                             List<LocalBodyRef> localBodyChoices, String confidence) {
        this(state, district, localBody, village, ward, wardMatch, wardChoices, villageChoices, localBodyChoices,
                confidence, null, "NONE".equals(confidence) ? "NONE" : "NAME_MATCH", null,
                ward != null ? "VERIFIED_BOUNDARY" : (wardChoices == null || wardChoices.isEmpty() ? null : "REQUIRES_VERIFICATION"));
    }

    public static AreaMatchResponse none(AreaRef state, DistrictRef district) {
        return new AreaMatchResponse(state, district, null, null, null, null, List.of(), List.of(), List.of(), "NONE",
                null, "NONE", null, null);
    }
}
