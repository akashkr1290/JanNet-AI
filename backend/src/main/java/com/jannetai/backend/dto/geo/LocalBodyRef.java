package com.jannetai.backend.dto.geo;

/**
 * A Gram Panchayat or urban body (local_bodies).
 *
 * @param kind     RURAL or URBAN
 * @param bodyType LGD type name, e.g. "Village Panchayat", "Municipal Corporation"
 */
public record LocalBodyRef(Integer code, String name, String kind, String bodyType, Integer districtCode) {
}
