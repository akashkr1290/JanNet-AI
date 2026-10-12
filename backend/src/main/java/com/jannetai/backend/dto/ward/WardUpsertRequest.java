package com.jannetai.backend.dto.ward;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Audit GAP-020 (SRS 15.11): create or replace a ward. {@code boundaryGeojson}
 * is a GeoJSON Polygon/MultiPolygon (or a Feature holding one) with
 * [longitude, latitude] positions; null or blank means "no boundary on record"
 * (the ward then cannot be found by GPS - WardLocator skips it). An update
 * replaces all fields.
 *
 * V34: {@code localBodyCode} - the Gram Panchayat / urban body the ward
 * belongs to, chosen in Admin with the location picker (null = legacy ward).
 * {@code centreLatitude/Longitude} - where the map opens for this ward when
 * it has no boundary (Admin taps the map). Ward names are unique per body.
 */
public record WardUpsertRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 30) String code,
        @Size(max = 2_000_000) String boundaryGeojson,
        Integer localBodyCode,
        @Size(max = 20) String wardNumber,
        @DecimalMin("6.0") @DecimalMax("38.0") BigDecimal centreLatitude,
        @DecimalMin("68.0") @DecimalMax("98.0") BigDecimal centreLongitude
) {
    /** Before V34 (name, code, boundary) - existing callers and tests. */
    public WardUpsertRequest(String name, String code, String boundaryGeojson) {
        this(name, code, boundaryGeojson, null, null, null, null);
    }
}
