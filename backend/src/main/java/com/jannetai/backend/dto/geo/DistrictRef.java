package com.jannetai.backend.dto.geo;

/**
 * An LGD district. {@code served} = this deployment accepts complaints from
 * it (geo_districts.is_served); the app warns the citizen when it is false.
 */
public record DistrictRef(Integer code, String name, Integer stateCode, String stateName, boolean served) {
}
