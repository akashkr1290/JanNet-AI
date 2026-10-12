package com.jannetai.backend.dto.geo;

import java.math.BigDecimal;

/**
 * A ward of a local body, as offered in the app's ward list.
 *
 * @param hasBoundary the ward has a boundary on record, so a pin inside it
 *                    finds it automatically (no choice needed)
 * @param localities  colony / mohalla names listed for the ward (helps the
 *                    citizen pick the right one), may be empty
 * @param source      V36: ADMIN, LGD (ward list) or SBM (Swachh Bharat Mission ward map)
 * @param verificationStatus V36: PUBLISHED, VERIFIED or UNVERIFIED
 */
public record WardRef(
        Long wardId,
        String name,
        String wardNumber,
        Integer localBodyCode,
        boolean hasBoundary,
        BigDecimal centreLatitude,
        BigDecimal centreLongitude,
        java.util.List<String> localities,
        String source,
        String verificationStatus
) {
    /** Pre-V36 shape (tests, legacy callers): an Admin ward. */
    public WardRef(Long wardId, String name, String wardNumber, Integer localBodyCode, boolean hasBoundary,
                   BigDecimal centreLatitude, BigDecimal centreLongitude, java.util.List<String> localities) {
        this(wardId, name, wardNumber, localBodyCode, hasBoundary, centreLatitude, centreLongitude, localities,
                "ADMIN", "VERIFIED");
    }
}
