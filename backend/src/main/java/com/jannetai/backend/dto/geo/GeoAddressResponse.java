package com.jannetai.backend.dto.geo;

/**
 * GET /api/v1/geo/reverse and GET /api/v1/geo/places/{id}: the address of
 * a point (from Google) and the LGD area it belongs to (from our tables).
 * The app shows {@code formattedAddress}/{@code landmark} under the pin and
 * sends {@code pincode}, {@code landmark}, {@code locality} and the area
 * codes back with the complaint (POST /api/v1/complaints).
 *
 * @param googleAvailable false when Google is switched off or today's quota
 *                        is used up
 * @param addressSource   V36: where {@code formattedAddress} comes from -
 *                        GOOGLE, OSM (OpenStreetMap Nominatim, road / colony
 *                        names only) or DATASET (built from our own area names)
 */
public record GeoAddressResponse(
        double latitude,
        double longitude,
        String formattedAddress,
        String pincode,
        String landmark,
        String locality,
        boolean googleAvailable,
        AreaMatchResponse area,
        String addressSource
) {
    public GeoAddressResponse(double latitude, double longitude, String formattedAddress, String pincode, String landmark,
                              String locality, boolean googleAvailable, AreaMatchResponse area) {
        this(latitude, longitude, formattedAddress, pincode, landmark, locality, googleAvailable, area,
                googleAvailable ? "GOOGLE" : "DATASET");
    }
}
