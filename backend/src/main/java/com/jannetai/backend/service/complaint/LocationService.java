package com.jannetai.backend.service.complaint;

import com.jannetai.backend.client.google.GoogleMapsClient;
import com.jannetai.backend.dto.complaint.IncidentArea;
import com.jannetai.backend.dto.complaint.IncidentLocationRequest;
import com.jannetai.backend.dto.geo.AreaMatchResponse;
import com.jannetai.backend.dto.geo.DistrictRef;
import com.jannetai.backend.dto.geo.LocalBodyRef;
import com.jannetai.backend.dto.geo.VillageRef;
import com.jannetai.backend.entity.GeoDistrict;
import com.jannetai.backend.entity.GeoVillage;
import com.jannetai.backend.entity.LocalBody;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.repository.LocationRepository;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.WardService;
import com.jannetai.backend.service.geo.AreaResolver;
import com.jannetai.backend.service.geo.GeoRepository;
import com.jannetai.backend.storage.ExifGps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * GPS Module subset actually needed by Phase 6 (SRS 15.5). What's
 * implemented: municipal-boundary bounds check (Location.outOfJurisdiction
 * flag, not a hard rejection - matches SRS "flagged... for Admin review",
 * not blocked) and optional ward attachment when the client supplies one.
 *
 * Later additions: ward-centroid fallback without coordinates (remaining-gaps
 * item 3, WARD_FALLBACK), polygon/nearest-ward reverse geocoding from the
 * wards' boundary GeoJSON (audit GAP-008), and EXIF GPS as the fallback when
 * the device sent no coordinates (audit GAP-031; read by ComplaintService via
 * ImageValidationService#readExifGps before the metadata strip, source EXIF).
 *
 * V33 (incident location): {@link #resolveIncident} is the entry point for
 * new complaints. The stored point is the INCIDENT location - where the
 * problem is - never automatically the citizen's position at submission. The
 * Flutter app proposes capture-time GPS or the photo's EXIF GPS, the citizen
 * confirms or corrects it on an OpenStreetMap map (or places a pin, or picks a
 * ward), and that confirmed point drives ward, jurisdiction, duplicates and
 * heatmaps exactly as before. The detected point, accuracy, photo time and
 * review flags ({@link LocationFlags}) are kept for staff.
 *
 * V34 (location upgrade): every new complaint also gets its real area -
 * LGD state, district, Gram Panchayat / urban body, village, PIN, address -
 * via {@link #applyArea}: the codes the citizen confirmed in the app are
 * checked against the LGD tables, or (older apps) worked out by
 * {@link AreaResolver} from Google's address. Jurisdiction then means "the
 * district is served" (geo_districts.is_served); the app.geo.municipal-*
 * box is only the fallback when no district is served or none is known.
 */
@Service
public class LocationService {

    private final LocationRepository locationRepository;
    private final WardService wardService;
    private final WardRepository wardRepository; // audit GAP-008: boundaries for reverse geocoding
    // V34 - null in the V33-era unit tests (3-argument constructor): area handling is then skipped.
    private final GeoRepository geoRepository;
    private final AreaResolver areaResolver;
    private final GoogleMapsClient googleMapsClient;

    /** V34: only to attach read-only GeoDistrict/LocalBody/GeoVillage references so the create response shows names. */
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    public LocationService(LocationRepository locationRepository, WardService wardService, WardRepository wardRepository,
                           GeoRepository geoRepository, AreaResolver areaResolver, GoogleMapsClient googleMapsClient) {
        this.locationRepository = locationRepository;
        this.wardService = wardService;
        this.wardRepository = wardRepository;
        this.geoRepository = geoRepository;
        this.areaResolver = areaResolver;
        this.googleMapsClient = googleMapsClient;
    }

    /** V33 behaviour only (no LGD area) - kept for the existing unit tests. */
    LocationService(LocationRepository locationRepository, WardService wardService, WardRepository wardRepository) {
        this(locationRepository, wardService, wardRepository, null, null, null);
    }

    @Value("${app.geo.municipal-min-latitude}")
    private BigDecimal minLatitude;
    @Value("${app.geo.municipal-max-latitude}")
    private BigDecimal maxLatitude;
    @Value("${app.geo.municipal-min-longitude}")
    private BigDecimal minLongitude;
    @Value("${app.geo.municipal-max-longitude}")
    private BigDecimal maxLongitude;

    /**
     * Audit GAP-008 (SRS 15.5 nearest-ward fallback): a GPS point outside every
     * ward boundary is assigned to the nearest ward if it is within this distance.
     */
    @Value("${app.geo.nearest-ward-max-meters:500}")
    private double nearestWardMaxMeters = 500;

    /** V33 review-flag thresholds (app.location.*) - warnings only. */
    @Value("${app.location.stale-photo-days:7}")
    private int stalePhotoDays = 7;
    @Value("${app.location.low-accuracy-meters:50}")
    private double lowAccuracyMeters = 50;
    @Value("${app.location.pin-moved-far-meters:200}")
    private double pinMovedFarMeters = 200;

    /** A photo time this far in the future is treated as a wrong device clock and ignored. */
    private static final long CAPTURED_AT_FUTURE_TOLERANCE_MINUTES = 10;
    private static final double MAX_ACCURACY_METERS = 100_000;
    private static final double MAX_DISTANCE_METERS = 21_000_000; // > half Earth's circumference

    /**
     * V33: resolves and saves the INCIDENT location of a new complaint.
     *
     * <p>Priority (product decision 2026-10-02, SRS 15.5 device GPS / EXIF
     * fallback / map pin / ward fallback):
     * <ol>
     *   <li>Citizen-confirmed point ({@code confirmed=true}, new clients): it is
     *       authoritative - whatever the source (CAPTURE_GPS, EXIF, MANUAL_PIN).
     *       The server never replaces it. With no coordinates and a ward, it is
     *       a ward fallback the citizen chose.</li>
     *   <li>Older clients (not confirmed): the photo's own EXIF GPS now wins over
     *       DEVICE_GPS, because DEVICE_GPS was read when the screen opened and
     *       may be the citizen's home (the bug this release fixes). The device
     *       position is reduced to a distance and discarded. An explicit
     *       MANUAL_PIN is kept. No coordinates: EXIF, else the ward fallback.</li>
     * </ol>
     *
     * @param exif GPS read by the server from the ORIGINAL upload (before the
     *             metadata strip), or null
     */
    @Transactional
    public Location resolveIncident(IncidentLocationRequest request, ExifGps.Coordinates exif) {
        BigDecimal latitude = request.latitude();
        BigDecimal longitude = request.longitude();
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("latitude and longitude must be provided together");
        }
        if ((request.detectedLatitude() == null) != (request.detectedLongitude() == null)) {
            throw new IllegalArgumentException("detectedLatitude and detectedLongitude must be provided together");
        }
        if (latitude != null) {
            requireCoordinate(latitude, 90, "latitude");
            requireCoordinate(longitude, 180, "longitude");
            if (request.source() == LocationSource.WARD_FALLBACK) {
                throw new IllegalArgumentException("WARD_FALLBACK is assigned by the server and cannot be sent with coordinates");
            }
        }
        if (request.detectedLatitude() != null) {
            requireCoordinate(request.detectedLatitude(), 90, "detectedLatitude");
            requireCoordinate(request.detectedLongitude(), 180, "detectedLongitude");
        }
        BigDecimal accuracy = nonNegative(request.accuracyMeters(), MAX_ACCURACY_METERS, "locationAccuracyMeters");
        BigDecimal submissionDistance = nonNegative(request.submissionDistanceMeters(), MAX_DISTANCE_METERS,
                "submissionDistanceMeters");
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime capturedAt = parseCapturedAt(request.capturedAt(), nowUtc);

        BigDecimal detectedLat = request.detectedLatitude();
        BigDecimal detectedLng = request.detectedLongitude();
        LocationSource source = request.source();
        Long wardId = request.wardId();
        boolean wardFallback = false;

        if (request.isConfirmed()) {
            if (latitude == null) {
                if (wardId == null) {
                    throw new IllegalArgumentException(
                            "A location is required: confirm the problem location on the map, or choose the ward");
                }
                wardFallback = true;
            } else if (source == null) {
                source = LocationSource.MANUAL_PIN;
            }
            if (detectedLat == null && exif != null) {
                // The client found no location in the photo but the server did:
                // keep it for staff comparison (never replaces the confirmed pin).
                detectedLat = exif.latitude();
                detectedLng = exif.longitude();
            }
        } else if (latitude == null) {
            if (exif != null) {
                latitude = exif.latitude();
                longitude = exif.longitude();
                source = LocationSource.EXIF;
                detectedLat = exif.latitude();
                detectedLng = exif.longitude();
            } else if (wardId == null) {
                throw new IllegalArgumentException(
                        "A location is required: send latitude and longitude, or choose your ward if GPS is unavailable "
                                + "(the photo has no GPS position either)");
            } else {
                wardFallback = true;
            }
        } else if (exif != null && (source == null || source == LocationSource.DEVICE_GPS)) {
            // Older client: GPS from when the screen opened. The photo knows
            // where it was taken - that is the incident location.
            if (submissionDistance == null) {
                submissionDistance = distance(latitude, longitude, exif.latitude(), exif.longitude());
            }
            latitude = exif.latitude();
            longitude = exif.longitude();
            source = LocationSource.EXIF;
            detectedLat = exif.latitude();
            detectedLng = exif.longitude();
            accuracy = null; // the device's accuracy described the discarded position
            wardId = null;   // re-derive the ward from the photo's position
        } else if (source == null) {
            source = LocationSource.DEVICE_GPS;
        }
        if (source == LocationSource.CAPTURE_GPS || source == LocationSource.EXIF) {
            if (detectedLat == null) {
                detectedLat = latitude;
                detectedLng = longitude;
            }
        }

        Location location = wardFallback
                ? buildWardFallback(wardId)
                : build(latitude, longitude, wardId, source, null);
        applyArea(location, request.area(), wardId != null && !wardFallback);
        if (accuracy != null && accuracy.doubleValue() > lowAccuracyMeters
                && "VERIFIED_BOUNDARY".equals(location.getLocationVerificationStatus())
                && location.getSource() != LocationSource.MANUAL_PIN) {
            // the GPS fix was too rough to trust the boundary result (could be the next ward)
            location.setLocationVerificationStatus("REQUIRES_VERIFICATION");
            location.setVerificationReason("LOW_GPS_ACCURACY");
        }
        location.setDetectedLatitude(scale6(detectedLat));
        location.setDetectedLongitude(scale6(detectedLng));
        location.setAccuracyMeters(accuracy);
        location.setCapturedAt(capturedAt);
        location.setConfirmedByCitizen(request.isConfirmed());
        location.setSubmissionDistanceMeters(submissionDistance);
        location.setFlags(LocationFlags.join(LocationFlags.compute(
                location.getLatitude(), location.getLongitude(), location.getDetectedLatitude(),
                location.getDetectedLongitude(), accuracy, capturedAt,
                Boolean.TRUE.equals(location.getOutOfJurisdiction()), nowUtc,
                new LocationFlags.Thresholds(stalePhotoDays, lowAccuracyMeters, pinMovedFarMeters))));
        return locationRepository.save(location);
    }

    private static void requireCoordinate(BigDecimal value, int bound, String field) {
        if (value.abs().compareTo(BigDecimal.valueOf(bound)) > 0) {
            throw new IllegalArgumentException(field + " must be between -" + bound + " and " + bound);
        }
    }

    private static BigDecimal nonNegative(Double value, double max, String field) {
        if (value == null) {
            return null;
        }
        if (value.isNaN() || value < 0 || value > max) {
            throw new IllegalArgumentException(field + " must be between 0 and " + (long) max);
        }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    /** ISO-8601 with an offset; stored as UTC. A time in the future (wrong clock) is ignored. */
    static LocalDateTime parseCapturedAt(String value, LocalDateTime nowUtc) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LocalDateTime utc;
        try {
            utc = OffsetDateTime.parse(value.trim()).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "photoCapturedAt must be an ISO-8601 date-time with offset, e.g. 2026-10-02T09:15:00+05:30");
        }
        return utc.isAfter(nowUtc.plusMinutes(CAPTURED_AT_FUTURE_TOLERANCE_MINUTES)) ? null : utc;
    }

    private static BigDecimal distance(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        return BigDecimal.valueOf(LocationFlags.distanceMeters(lat1.doubleValue(), lng1.doubleValue(),
                lat2.doubleValue(), lng2.doubleValue())).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale6(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }

    @Transactional
    public Location resolveAndSave(BigDecimal latitude, BigDecimal longitude, Long wardId,
                                    LocationSource source, String formattedAddress) {
        return locationRepository.save(build(latitude, longitude, wardId, source, formattedAddress));
    }

    private Location build(BigDecimal latitude, BigDecimal longitude, Long wardId,
                           LocationSource source, String formattedAddress) {
        boolean outOfJurisdiction = latitude.compareTo(minLatitude) < 0
                || latitude.compareTo(maxLatitude) > 0
                || longitude.compareTo(minLongitude) < 0
                || longitude.compareTo(maxLongitude) > 0;

        Ward ward = null;
        if (wardId != null) {
            // Client-supplied ward (manual selection) - reuse WardService's
            // existing active-ward validation (Phase 5), same as profile update.
            ward = wardService.requireActiveWardEntity(wardId);
        } else if (!outOfJurisdiction) {
            // Audit GAP-008: GPS submissions used to keep ward_id NULL, which
            // skipped duplicate detection and the ward heatmap for them.
            ward = resolveWard(latitude.doubleValue(), longitude.doubleValue());
        }

        Location location = Location.builder()
                .latitude(latitude)
                .longitude(longitude)
                .ward(ward)
                .formattedAddress(formattedAddress)
                .source(source != null ? source : LocationSource.DEVICE_GPS)
                .outOfJurisdiction(outOfJurisdiction)
                .build();

        return location;
    }

    /**
     * Remaining-gaps item 3: GPS unavailable and no coordinates supplied. The
     * ward (chosen by the citizen) is exact; the stored point is an
     * approximation - the ward boundary's centroid when a boundary is on
     * record, otherwise the centre of the configured municipal area
     * (app.geo.*). Source WARD_FALLBACK marks it as approximate everywhere
     * downstream.
     */
    @Transactional
    public Location resolveWardFallbackAndSave(Long wardId) {
        return locationRepository.save(buildWardFallback(wardId));
    }

    private Location buildWardFallback(Long wardId) {
        Ward ward = wardService.requireActiveWardEntity(wardId);
        double[] point = WardCentroid.of(ward.getBoundaryGeojson())
                .or(() -> ward.getCentreLatitude() == null ? java.util.Optional.<double[]>empty() // V34: Admin-set ward centre
                        : java.util.Optional.of(new double[]{ward.getCentreLatitude().doubleValue(),
                        ward.getCentreLongitude().doubleValue()}))
                .orElseGet(() -> new double[]{
                        minLatitude.add(maxLatitude).doubleValue() / 2,
                        minLongitude.add(maxLongitude).doubleValue() / 2});
        Location location = Location.builder()
                .latitude(BigDecimal.valueOf(point[0]).setScale(6, java.math.RoundingMode.HALF_UP))
                .longitude(BigDecimal.valueOf(point[1]).setScale(6, java.math.RoundingMode.HALF_UP))
                .ward(ward)
                .formattedAddress("Approximate location: " + ward.getName() + " (GPS unavailable)")
                .source(LocationSource.WARD_FALLBACK)
                .outOfJurisdiction(false)
                .build();
        return location;
    }

    /**
     * V34 + V36: fills the area of a new complaint's location, decides
     * jurisdiction by served district, and records whether the area is
     * PROVEN (spec 2026-10-11). Called once from {@link #resolveIncident}.
     *
     * <ol>
     *   <li>The boundaries say where the point is ({@link AreaResolver#resolvePoint}:
     *       published ward / town / village / PIN polygons, Google names only
     *       where no polygon covers the point).</li>
     *   <li>The app sent codes (citizen confirmed "Is this place X?" or chose
     *       from the lists): they are checked - the village must belong to the
     *       body, a chosen ward to the same body - and stored. A wrong
     *       combination is a 400, never silently changed. Codes that differ from
     *       the boundaries are kept (the citizen confirmed the place) but marked
     *       for verification.</li>
     *   <li>No codes: the boundary area is stored when EXACT or LIKELY.</li>
     *   <li>Ward: set automatically ONLY from a ward boundary (BOUNDARY). A ward
     *       the citizen chose without a boundary to prove it is kept as CITIZEN
     *       and the location is REQUIRES_VERIFICATION; a nearest / colony-name
     *       ward is only stored as {@code suggestedWard}.</li>
     * </ol>
     *
     * @param citizenChoseWard the request carried a wardId together with coordinates
     */
    void applyArea(Location location, IncidentArea area, boolean citizenChoseWard) {
        if (geoRepository == null) {
            return; // V33-era unit tests
        }
        location.setPincode(validPincode(area.pincode()));
        location.setLandmark(trim(area.landmark(), 150));
        location.setLocality(trim(area.locality(), 150));
        if (area.formattedAddress() != null && !area.formattedAddress().isBlank()) {
            location.setFormattedAddress(trim(area.formattedAddress(), 300));
        }
        boolean realPoint = location.getSource() != LocationSource.WARD_FALLBACK;
        double lat = location.getLatitude().doubleValue();
        double lng = location.getLongitude().doubleValue();

        // 1. what the published boundaries (or, without them, names) say about the point
        AreaMatchResponse found = realPoint && areaResolver != null ? areaResolver.resolvePoint(lat, lng) : null;
        boolean foundTrusted = found != null && ("EXACT".equals(found.confidence()) || "LIKELY".equals(found.confidence()));
        boolean byBoundary = found != null && "BOUNDARY".equals(found.areaSource());

        LocalBodyRef body = null;
        VillageRef village = null;
        DistrictRef district = null;
        String reason = null;
        if (area.hasCodes()) {
            // 2. the citizen's confirmed codes
            if (area.localBodyCode() != null) {
                body = geoRepository.localBody(area.localBodyCode())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown localBodyCode " + area.localBodyCode()));
            }
            if (area.villageCode() != null) {
                village = geoRepository.village(area.villageCode())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown villageCode " + area.villageCode()));
                if (body == null && village.localBodies().size() == 1) {
                    body = village.localBodies().get(0);
                }
                LocalBodyRef chosen = body;
                if (chosen != null && village.localBodies().stream().noneMatch(b -> b.code().equals(chosen.code()))) {
                    throw new IllegalArgumentException("Village " + village.name() + " is not part of " + chosen.name());
                }
            }
            Integer districtCode = village != null ? village.districtCode() : body != null ? body.districtCode() : null;
            district = districtCode == null ? null : geoRepository.district(districtCode).orElse(null);
            if (!byBoundary || found.localBody() == null) {
                reason = "AREA_CHOSEN_BY_CITIZEN";
            } else if (body != null && !body.code().equals(found.localBody().code())) {
                reason = "AREA_DIFFERS_FROM_MAP";
            }
        } else if (found != null) {
            // 3. no codes (older app): the resolver's area - not a district that was
            // only guessed from the nearest post office
            district = "POST_OFFICE".equals(found.areaSource()) ? null : found.district();
            if (foundTrusted) {
                body = found.localBody();
                village = found.village();
            }
            if (location.getPincode() == null && found.pincode() != null) {
                location.setPincode(found.pincode());
            }
            if (!byBoundary) {
                reason = body != null ? "AREA_FROM_ADDRESS_NAMES" : "AREA_UNKNOWN";
            }
        }
        if (location.getPincode() == null && byBoundary && found.pincode() != null) {
            location.setPincode(found.pincode());
        }

        if (body != null) {
            location.setLocalBodyCode(body.code());
        }
        if (village != null) {
            location.setVillageCode(village.code());
        }
        if (district != null) {
            location.setDistrictCode(district.code());
            location.setStateCode(district.stateCode());
        }

        // 4. ward inside the body
        Ward ward = location.getWard();
        Long provenWardId = found != null && found.ward() != null ? found.ward().wardId() : null;
        Long suggestedWardId = found != null && found.suggestedWard() != null ? found.suggestedWard().wardId() : null;
        if (ward != null && ward.getLocalBodyCode() != null) {
            if (body == null) {
                // a ward was chosen without codes: the ward tells the body
                location.setLocalBodyCode(ward.getLocalBodyCode());
                geoRepository.localBody(ward.getLocalBodyCode()).map(LocalBodyRef::districtCode)
                        .flatMap(geoRepository::district).ifPresent(d -> {
                            location.setDistrictCode(d.code());
                            location.setStateCode(d.stateCode());
                        });
            } else if (!ward.getLocalBodyCode().equals(body.code())) {
                throw new IllegalArgumentException("Ward " + ward.getName() + " is not part of " + body.name());
            }
            if (provenWardId != null && provenWardId.equals(ward.getWardId())) {
                location.setWardMatch("BOUNDARY"); // the citizen picked the ward the map proves
            } else {
                location.setWardMatch(citizenChoseWard || !realPoint ? "CITIZEN" : "LEGACY");
                if (reason == null) {
                    reason = provenWardId != null ? "WARD_DIFFERS_FROM_MAP"
                            : (!realPoint ? "NO_COORDINATES" : "WARD_CHOSEN_BY_CITIZEN");
                }
                if (provenWardId != null) {
                    suggestedWardId = provenWardId;
                }
            }
        } else if (ward == null && location.getLocalBodyCode() != null && realPoint) {
            Integer bodyCode = location.getLocalBodyCode();
            if (provenWardId != null && Objects.equals(found.ward().localBodyCode(), bodyCode)) {
                location.setWard(wardRepository.findById(provenWardId).orElse(null));
                location.setWardMatch(location.getWard() != null ? "BOUNDARY" : null);
            } else if (areaResolver != null) {
                Optional<AreaResolver.WardHit> hit = areaResolver.findWard(bodyCode, lat, lng, List.of());
                hit.ifPresent(h -> {
                    location.setWard(wardRepository.findById(h.wardId()).orElse(null));
                    location.setWardMatch(location.getWard() != null ? "BOUNDARY" : null);
                });
            }
            if (location.getWard() == null && reason == null && !geoRepository.wardsOfBody(bodyCode).isEmpty()) {
                reason = "NO_WARD_BOUNDARY"; // the body has wards, but no map proves which one
            }
        } else if (ward != null) {
            location.setWardMatch("LEGACY");
        }
        if (!realPoint && reason == null) {
            reason = "NO_COORDINATES";
        }
        if (location.getLocalBodyCode() == null && location.getWard() == null && reason == null) {
            reason = "AREA_UNKNOWN";
        }
        if (suggestedWardId != null && (location.getWard() == null || !suggestedWardId.equals(location.getWard().getWardId()))) {
            location.setSuggestedWard(wardRepository.findById(suggestedWardId).orElse(null));
        }
        location.setLocationVerificationStatus(reason == null ? "VERIFIED_BOUNDARY" : "REQUIRES_VERIFICATION");
        location.setVerificationReason(reason);

        if (entityManager != null) {
            // names for LocationResponse right after create (the columns themselves are written via the codes)
            if (location.getDistrictCode() != null) {
                location.setDistrict(entityManager.getReference(GeoDistrict.class, location.getDistrictCode()));
            }
            if (location.getLocalBodyCode() != null) {
                location.setLocalBody(entityManager.getReference(LocalBody.class, location.getLocalBodyCode()));
            }
            if (location.getVillageCode() != null) {
                location.setVillage(entityManager.getReference(GeoVillage.class, location.getVillageCode()));
            }
        }

        // jurisdiction: the served districts decide once any is configured
        if (location.getDistrictCode() != null && geoRepository.anyDistrictServed()) {
            boolean served = geoRepository.district(location.getDistrictCode()).map(DistrictRef::served).orElse(false);
            location.setOutOfJurisdiction(!served);
        }
    }

    /**
     * V36 verification queue: staff confirm or correct the ward (and body) of a
     * complaint whose location was REQUIRES_VERIFICATION. The ward must be an
     * active ward; its body becomes the location's body. Status STAFF_VERIFIED,
     * ward_match STAFF. Called by ComplaintService#verifyLocation.
     */
    @Transactional
    public Location verifyByStaff(Location location, Long wardId) {
        if (wardId != null) {
            Ward ward = wardService.requireActiveWardEntity(wardId);
            location.setWard(ward);
            location.setWardMatch("STAFF");
            if (ward.getLocalBodyCode() != null && geoRepository != null) {
                location.setLocalBodyCode(ward.getLocalBodyCode());
                geoRepository.localBody(ward.getLocalBodyCode()).map(LocalBodyRef::districtCode)
                        .flatMap(geoRepository::district).ifPresent(d -> {
                            location.setDistrictCode(d.code());
                            location.setStateCode(d.stateCode());
                            if (geoRepository.anyDistrictServed()) {
                                location.setOutOfJurisdiction(!d.served());
                            }
                        });
            }
        }
        location.setSuggestedWard(null);
        location.setLocationVerificationStatus("STAFF_VERIFIED");
        location.setVerificationReason(null);
        return locationRepository.save(location);
    }

    private static String validPincode(String pincode) {
        if (pincode == null) {
            return null;
        }
        String p = pincode.replace(" ", "");
        if (!p.matches("[1-9][0-9]{5}")) {
            throw new IllegalArgumentException("pincode must be a 6-digit Indian PIN code");
        }
        return p;
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    /**
     * Audit GAP-008: reverse-geocodes a GPS point to an active ward using the
     * wards' boundary GeoJSON (point-in-polygon, then nearest ward within
     * app.geo.nearest-ward-max-meters). Wards without a boundary are skipped;
     * returns null when no ward matches - the complaint is still accepted.
     */
    Ward resolveWard(double latitude, double longitude) {
        // V34: only legacy wards (no local body) are searched across the whole
        // deployment; LGD wards are searched within their body (applyArea).
        List<Ward> wards = wardRepository.findByLocalBodyCodeIsNullAndIsActiveTrueOrderByNameAsc();
        List<WardLocator.WardShape> shapes = WardLocator.shapesOf(
                wards.stream().map(Ward::getWardId).toList(),
                wards.stream().map(Ward::getBoundaryGeojson).toList());
        // V36 (spec 2026-10-11): only a boundary that CONTAINS the point assigns a ward
        return WardLocator.locate(shapes, latitude, longitude, 0)
                .flatMap(id -> wards.stream().filter(w -> w.getWardId().equals(id)).findFirst())
                .orElse(null);
    }
}
