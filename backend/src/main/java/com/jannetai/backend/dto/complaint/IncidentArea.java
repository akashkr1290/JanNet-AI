package com.jannetai.backend.dto.complaint;

/**
 * V34: the address and LGD area the citizen confirmed in the app, sent with
 * POST /api/v1/complaints (multipart fields pincode, landmark, locality,
 * formattedAddress, villageCode, localBodyCode). The app got them from
 * GET /api/v1/geo/reverse | /places/{id} | /pincodes/{pin} (GeoController).
 * LocationService#applyArea checks the codes against the LGD tables
 * (village belongs to the body, ward belongs to the body) - text fields are
 * display only and are trimmed to their column sizes.
 *
 * All fields optional: older apps send none, and LocationService then works
 * the area out itself (AreaResolver).
 */
public record IncidentArea(
        String pincode,
        String landmark,
        String locality,
        String formattedAddress,
        Integer villageCode,
        Integer localBodyCode
) {
    public static final IncidentArea NONE = new IncidentArea(null, null, null, null, null, null);

    public boolean hasCodes() {
        return localBodyCode != null || villageCode != null;
    }
}
