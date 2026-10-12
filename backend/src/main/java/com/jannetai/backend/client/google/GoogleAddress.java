package com.jannetai.backend.client.google;

import java.util.List;

/**
 * An address from Google, flattened to what JanNet needs. Built by
 * {@link GoogleMapsClient#parseGeocode} (Geocoding API, snake_case) and
 * {@link GoogleMapsClient#parsePlace} (Places API (New), camelCase) - the two
 * APIs name the same parts differently.
 *
 * <p>Read by service/geo/AreaResolver, which matches {@code stateName},
 * {@code districtNames} and {@code localityNames} against the LGD tables.
 *
 * @param districtNames India: administrative_area_level_3 is usually the
 *                      district and level_2 the division (or the district in
 *                      some states) - both are kept, most specific first
 * @param localityNames village / colony / sector / town names, most specific
 *                      first (neighborhood, sublocality levels, locality)
 * @param placeName     landmark or establishment name, when the address is a place
 */
public record GoogleAddress(
        String formattedAddress,
        double latitude,
        double longitude,
        String pincode,
        String stateName,
        List<String> districtNames,
        List<String> localityNames,
        String route,
        String placeName
) {
}
