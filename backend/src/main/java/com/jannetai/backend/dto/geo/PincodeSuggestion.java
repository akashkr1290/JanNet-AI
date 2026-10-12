package com.jannetai.backend.dto.geo;

/**
 * GET /api/v1/geo/pincodes?prefix=8244: PIN codes starting with the digits
 * typed so far, with one post office and the district to recognise them.
 * Only PINs present in the loaded data are returned - never made up.
 */
public record PincodeSuggestion(String pincode, String officeName, Integer districtCode, String districtName,
                                String stateName, boolean served) {
}
