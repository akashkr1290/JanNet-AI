package com.jannetai.backend.dto.geo;

import java.math.BigDecimal;
import java.util.List;

/**
 * An LGD revenue village with where it sits: sub-district (tehsil),
 * block, and the Gram Panchayat(s) / urban body covering it. The centre is
 * null unless loaded from a village boundary file or set by Admin.
 *
 * @param blockCode V37: LGD development block code (geo_villages.block_code,
 *                  set by geo_import from LGD villages_by_blocks); null when
 *                  LGD gives no block for the village
 */
public record VillageRef(
        Integer code,
        String name,
        Integer districtCode,
        String subdistrictName,
        String blockName,
        List<LocalBodyRef> localBodies,
        BigDecimal centreLatitude,
        BigDecimal centreLongitude,
        Integer blockCode
) {
    /** Before V37 (no block code) - existing callers and tests. */
    public VillageRef(Integer code, String name, Integer districtCode, String subdistrictName, String blockName,
                      List<LocalBodyRef> localBodies, BigDecimal centreLatitude, BigDecimal centreLongitude) {
        this(code, name, districtCode, subdistrictName, blockName, localBodies, centreLatitude, centreLongitude, null);
    }
}
