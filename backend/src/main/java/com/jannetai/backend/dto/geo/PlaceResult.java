package com.jannetai.backend.dto.geo;

/**
 * GET /api/v1/geo/search: one search result for the map's search box. The app
 * moves the pin to ({@code latitude}, {@code longitude}) and then asks
 * /geo/reverse for the area, so a result never decides the area by itself.
 *
 * @param kind   PINCODE, POST_OFFICE, WARD, LOCAL_BODY, VILLAGE (our dataset)
 *               or PLACE (an OpenStreetMap landmark / road)
 * @param source DATASET or OSM
 */
public record PlaceResult(String label, String secondary, double latitude, double longitude, String kind,
                          String source) {
}
