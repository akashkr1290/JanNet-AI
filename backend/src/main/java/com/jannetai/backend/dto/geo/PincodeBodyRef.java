package com.jannetai.backend.dto.geo;

/**
 * A local body a PIN code covers (pincode_local_bodies, V36). A PIN usually
 * covers several Gram Panchayats and/or a town, and a town several PINs.
 *
 * @param mappingSource      LGD_VILLAGE_PIN (LGD village -> PIN list), LGD_URBAN_PIN
 *                           (LGD urban body -> PIN list), BOUNDARY_OVERLAP or ADMIN
 * @param verificationStatus PUBLISHED, VERIFIED or UNVERIFIED
 * @param villageCount       villages of the body under this PIN (LGD_VILLAGE_PIN)
 */
public record PincodeBodyRef(LocalBodyRef localBody, String mappingSource, String verificationStatus, int villageCount) {
}
