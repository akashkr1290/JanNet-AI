package com.jannetai.backend.entity;

import com.jannetai.backend.entity.enums.LocationSource;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The complaint's INCIDENT location - where the problem is, which is not
 * necessarily where the citizen was when submitting. Maps onto
 * database/migrations/V5__create_locations.sql (+ V23, V33).
 */
@Entity
@Table(name = "locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ward_id")
    private Ward ward;

    @Column(name = "formatted_address", length = 300)
    private String formattedAddress;

    /** V34: PIN code of the incident point (Google address, or the citizen's manual PIN). */
    @Column(name = "pincode", length = 6, columnDefinition = "CHAR(6)")
    private String pincode;

    /** V34: landmark / place name, e.g. "Republic Crossing" (search result or Google place). */
    @Column(name = "landmark", length = 150)
    private String landmark;

    /** V34: colony / sector / mohalla from Google's address. */
    @Column(name = "locality", length = 150)
    private String locality;

    /**
     * V34: LGD area codes resolved by LocationService (from the app's
     * confirmed choice, or AreaResolver). The read-only associations below
     * share these columns and only serve names for display (LocationResponse).
     */
    @Column(name = "state_code", columnDefinition = "INT UNSIGNED")
    private Integer stateCode;

    @Column(name = "district_code", columnDefinition = "INT UNSIGNED")
    private Integer districtCode;

    @Column(name = "local_body_code", columnDefinition = "INT UNSIGNED")
    private Integer localBodyCode;

    @Column(name = "village_code", columnDefinition = "INT UNSIGNED")
    private Integer villageCode;

    /** V37: development block of the village / Gram Panchayat; null in a town or when unknown. */
    @Column(name = "block_code", columnDefinition = "INT UNSIGNED")
    private Integer blockCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "district_code", insertable = false, updatable = false)
    private GeoDistrict district;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_body_code", insertable = false, updatable = false)
    private LocalBody localBody;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "village_code", insertable = false, updatable = false)
    private GeoVillage village;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "block_code", insertable = false, updatable = false)
    private GeoBlock block;

    /**
     * V34: how the ward was found - BOUNDARY (point inside a published ward
     * map), CITIZEN (chosen in the app), STAFF (set in the verification
     * queue), LEGACY; LOCALITY / NEAREST only on rows created before V36.
     */
    @Column(name = "ward_match", length = 12)
    private String wardMatch;

    /**
     * V36: VERIFIED_BOUNDARY (area and ward proven by published boundaries),
     * REQUIRES_VERIFICATION (staff must confirm - see verificationReason) or
     * STAFF_VERIFIED. Set by LocationService#applyArea / #verifyByStaff.
     */
    @Column(name = "location_verification_status", length = 24)
    private String locationVerificationStatus;

    /** V36: why verification is needed, e.g. NO_WARD_BOUNDARY, WARD_CHOSEN_BY_CITIZEN (LocationService). */
    @Column(name = "verification_reason", length = 40)
    private String verificationReason;

    /** V36: a likely ward when none could be proven (nearest ward / listed colony) - shown to staff, never routed on. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suggested_ward_id")
    private Ward suggestedWard;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private LocationSource source;

    @Column(name = "out_of_jurisdiction", nullable = false)
    private Boolean outOfJurisdiction;

    /** V33: device-reported GPS accuracy radius (metres) of the detected fix, when known. */
    @Column(name = "accuracy_m", precision = 9, scale = 1)
    private BigDecimal accuracyMeters;

    /** V33: when the photo was taken (UTC), when known. */
    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    /**
     * V33: the automatically detected point (capture GPS or photo EXIF). Kept
     * even when the citizen moved the pin, so staff can compare both.
     */
    @Column(name = "detected_latitude", precision = 9, scale = 6)
    private BigDecimal detectedLatitude;

    @Column(name = "detected_longitude", precision = 9, scale = 6)
    private BigDecimal detectedLongitude;

    /** V33: the citizen confirmed this point on the map (false for older clients). */
    @Builder.Default
    @Column(name = "confirmed_by_citizen", nullable = false)
    private Boolean confirmedByCitizen = Boolean.FALSE;

    /** V33: comma-separated review flags (see LocationFlags) - warnings only, never blocking. */
    @Column(name = "flags", length = 200)
    private String flags;

    /**
     * V33: distance (metres) between this point and where the citizen was when
     * submitting, when known. The citizen's own coordinates are never stored.
     */
    @Column(name = "submission_distance_m", precision = 10, scale = 1)
    private BigDecimal submissionDistanceMeters;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
