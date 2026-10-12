package com.jannetai.backend.dto.geo;

import java.math.BigDecimal;
import java.util.List;

/**
 * An LGD revenue village with where it sits: sub-district (tehsil),
 * block, and the Gram Panchayat(s) / urban body covering it. The centre is
 * null unless loaded from a village boundary file or set by Admin.
 */
public record VillageRef(
        Integer code,
        String name,
        Integer districtCode,
        String subdistrictName,
        String blockName,
        List<LocalBodyRef> localBodies,
        BigDecimal centreLatitude,
        BigDecimal centreLongitude
) {
}
