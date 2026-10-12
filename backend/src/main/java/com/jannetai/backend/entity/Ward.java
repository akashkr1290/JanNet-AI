package com.jannetai.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Administrative wards/zones for location-based routing.
 * Maps onto database/migrations/V1__create_wards.sql (+ V34: local body, LGD code, number, centre).
 */
@Entity
@Table(name = "wards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ward_id")
    private Long wardId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "code", length = 30)
    private String code;

    /**
     * V34: the Gram Panchayat / urban body this ward belongs to (LGD code,
     * local_bodies). NULL = a legacy pilot ward (Ward 1/2/3, UNASSIGNED).
     * Ward lists in the app are per body: GeoRepository#wardsOfBody.
     */
    @Column(name = "local_body_code", columnDefinition = "INT UNSIGNED")
    private Integer localBodyCode;

    /** V34: LGD ward code when imported from LGD (database/tools/lgd_import), else null. */
    @Column(name = "lgd_ward_code", columnDefinition = "INT UNSIGNED")
    private Integer lgdWardCode;

    /** V34: ward number as printed by the body ("12", "12A"). */
    @Column(name = "ward_number", length = 20)
    private String wardNumber;

    /**
     * V36: where the ward comes from - ADMIN (created in Admin -> Wards), LGD
     * (ward list) or SBM (Swachh Bharat Mission ward map), loaded by
     * database/tools/geo_import. {@code sourceRef} is the id in that source.
     */
    @Builder.Default
    @Column(name = "source", nullable = false, length = 30)
    private String source = "ADMIN";

    @Column(name = "source_ref", length = 80)
    private String sourceRef;

    @Column(name = "source_date")
    private java.time.LocalDate sourceDate;

    /**
     * V36: PUBLISHED (from a government portal), VERIFIED (confirmed in this
     * deployment - wards an Admin creates count as confirmed) or UNVERIFIED.
     * Only PUBLISHED / VERIFIED ward maps assign a ward automatically.
     */
    @Builder.Default
    @Column(name = "verification_status", nullable = false, length = 12)
    private String verificationStatus = "VERIFIED";

    /** GeoJSON text (Polygon/MultiPolygon); stored as MySQL JSON. Audit GAP-008: used by LocationService to reverse-geocode GPS points (WardLocator). */
    @Column(name = "boundary_geojson", columnDefinition = "json")
    private String boundaryGeojson;

    /** V34: map centre when there is no boundary (set by Admin); the app opens the map here. */
    @Column(name = "centre_latitude", precision = 9, scale = 6)
    private BigDecimal centreLatitude;

    @Column(name = "centre_longitude", precision = 9, scale = 6)
    private BigDecimal centreLongitude;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
