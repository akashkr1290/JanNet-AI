package com.jannetai.backend.service.geo;

import com.jannetai.backend.client.google.GoogleAddress;
import com.jannetai.backend.client.google.GoogleMapsClient;
import com.jannetai.backend.client.osm.NominatimClient;
import com.jannetai.backend.dto.geo.AreaMatchResponse;
import com.jannetai.backend.dto.geo.AreaRef;
import com.jannetai.backend.dto.geo.DistrictRef;
import com.jannetai.backend.dto.geo.GeoAddressResponse;
import com.jannetai.backend.dto.geo.LocalBodyRef;
import com.jannetai.backend.dto.geo.VillageRef;
import com.jannetai.backend.dto.geo.WardRef;
import com.jannetai.backend.service.complaint.LocationFlags;
import com.jannetai.backend.service.complaint.WardLocator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Works out WHICH government area a point belongs to: state -> district ->
 * Gram Panchayat / urban body -> village -> ward (+ PIN).
 *
 * <p>Called by:
 * <ul>
 *   <li>controller/GeoController - /geo/reverse (pin dropped or moved) and
 *       /geo/places/{id} (Google result picked, only when Google is on);</li>
 *   <li>service/complaint/LocationService#applyArea - every new complaint.</li>
 * </ul>
 *
 * <p>Order of evidence (spec "Intelligent Geographical Location Detection",
 * 2026-10-11 - never invent an area, never assign a ward from an approximate
 * address match):
 * <ol>
 *   <li><b>Boundaries</b> (V36 geo_boundaries, loaded by database/tools/geo_import):
 *       the published polygons containing the point give ward, town / Gram
 *       Panchayat, village and PIN ({@link #fromBoundaries}). Only a ward map
 *       with status PUBLISHED or VERIFIED assigns a ward. areaSource BOUNDARY.</li>
 *   <li><b>Names</b> - only where no polygon covers the point: Google's address
 *       names matched to LGD (when Google is configured), else the district of
 *       the nearest post office with choice lists. areaSource NAME_MATCH /
 *       POST_OFFICE. A ward is never set here; the citizen chooses and the
 *       complaint is marked REQUIRES_VERIFICATION by LocationService.</li>
 * </ol>
 * Proximity (nearest ward) and listed colony names only produce
 * {@code suggestedWard} - a hint for the citizen and staff.
 */
@Service
public class AreaResolver {

    private final GeoRepository geo;
    private final GoogleMapsClient google;
    private final NominatimClient osm;

    @Value("${app.geo.nearest-ward-max-meters:500}")
    private double nearestWardMaxMeters = 500;

    @Autowired
    public AreaResolver(GeoRepository geo, GoogleMapsClient google, NominatimClient osm) {
        this.geo = geo;
        this.google = google;
        this.osm = osm;
    }

    /** Unit tests: no OpenStreetMap. */
    AreaResolver(GeoRepository geo, GoogleMapsClient google) {
        this(geo, google, null);
    }

    // ------------------------------------------------------------------ entry points

    /**
     * /geo/reverse: the area of a pin, plus an address line. The address comes
     * from Google when configured, else from OpenStreetMap road / colony names
     * followed by our own area names (ward, town, district, state, PIN).
     */
    public GeoAddressResponse describePoint(double lat, double lng) {
        Optional<GoogleAddress> g = google.reverseGeocode(lat, lng);
        if (g.isPresent()) {
            return describe(lat, lng, g.get(), null);
        }
        AreaMatchResponse area = resolve(lat, lng, null);
        Optional<NominatimClient.OsmAddress> o = osm != null ? osm.reverse(lat, lng) : Optional.empty();
        List<String> localities = o.map(NominatimClient.OsmAddress::localityNames).orElse(List.of());
        String place = o.map(NominatimClient.OsmAddress::placeName).orElse(null);
        String locality = localities.isEmpty() ? null : localities.get(0);
        return new GeoAddressResponse(lat, lng, addressLine(localities, area), area.pincode(), place, locality,
                false, area, o.isPresent() ? "OSM" : "DATASET");
    }

    /** /geo/places/{id}: coordinates + address of a picked Google result + its area. */
    public Optional<GeoAddressResponse> describePlace(String placeId, String sessionToken, String landmark) {
        return google.placeDetails(placeId, sessionToken)
                .map(a -> describe(a.latitude(), a.longitude(), a, landmark));
    }

    private GeoAddressResponse describe(double lat, double lng, GoogleAddress a, String landmark) {
        AreaMatchResponse area = resolve(lat, lng, a);
        // the PIN from a published boundary wins over Google's postal code
        String pin = area.pincode() != null ? area.pincode() : a.pincode();
        String place = landmark != null && !landmark.isBlank() ? landmark.trim() : a.placeName();
        return new GeoAddressResponse(lat, lng, a.formattedAddress(), pin, place,
                !a.localityNames().isEmpty() ? a.localityNames().get(0) : null, true, area, "GOOGLE");
    }

    /**
     * The area of a point. {@code address} (Google) is used only when no
     * published boundary covers the point; it may be null.
     */
    public AreaMatchResponse resolve(double lat, double lng, GoogleAddress address) {
        Optional<AreaMatchResponse> fromMaps = fromBoundaries(lat, lng, address != null ? address.localityNames() : List.of());
        if (fromMaps.isPresent()) {
            return fromMaps.get();
        }
        return fromNames(lat, lng, address);
    }

    /**
     * Area for a new complaint (LocationService): boundaries first; Google is
     * called only when no boundary covers the point.
     */
    public AreaMatchResponse resolvePoint(double lat, double lng) {
        Optional<AreaMatchResponse> fromMaps = fromBoundaries(lat, lng, List.of());
        if (fromMaps.isPresent()) {
            return fromMaps.get();
        }
        return fromNames(lat, lng, google.reverseGeocode(lat, lng).orElse(null));
    }

    // ------------------------------------------------------------------ 1. boundaries

    /**
     * The area from the polygons containing the point. Each layer is used only
     * when exactly one polygon of it contains the point (overlapping polygons
     * of one layer = ambiguous = not used). Priority for the local body: the
     * ward's body, then a town boundary, then the village's only body, then a
     * Gram Panchayat boundary.
     */
    Optional<AreaMatchResponse> fromBoundaries(double lat, double lng, List<String> localityNames) {
        List<GeoRepository.BoundaryHit> hits = geo.containing(lat, lng);
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        WardRef ward = only(hits, "WARD", true).flatMap(id -> geo.activeWard(Long.parseLong(id))).orElse(null);
        Optional<String> town = only(hits, "LOCAL_BODY", false);
        Optional<String> villageCode = only(hits, "VILLAGE", false);
        Optional<String> gp = only(hits, "PANCHAYAT", false);
        String pincode = only(hits, "PINCODE", false).orElse(null);

        VillageRef village = villageCode.flatMap(c -> geo.village(Integer.parseInt(c))).orElse(null);
        LocalBodyRef body = null;
        if (ward != null && ward.localBodyCode() != null) {
            body = geo.localBody(ward.localBodyCode()).orElse(null);
        }
        if (body == null && town.isPresent()) {
            body = geo.localBody(Integer.parseInt(town.get())).orElse(null);
        }
        if (body == null && village != null && village.localBodies().size() == 1) {
            body = village.localBodies().get(0);
        }
        if (body == null && gp.isPresent()) {
            body = geo.localBody(Integer.parseInt(gp.get())).orElse(null);
        }
        if (body == null && village == null) {
            return Optional.empty(); // only a PIN polygon - not enough for an area
        }
        Integer districtCode = village != null ? village.districtCode() : body.districtCode();
        DistrictRef district = districtCode == null ? null : geo.district(districtCode).orElse(null);
        AreaRef state = district != null ? new AreaRef(district.stateCode(), district.stateName()) : null;

        List<WardRef> wardChoices = body != null ? geo.wardsOfBody(body.code()) : List.of();
        if (ward == null && body != null) {
            // an Admin-drawn ward boundary (wards.boundary_geojson) also proves the ward
            ward = adminDrawnWard(body.code(), wardChoices, lat, lng).orElse(null);
        }
        WardRef suggested = null;
        String wardVerification = null;
        if (ward != null) {
            wardVerification = "VERIFIED_BOUNDARY";
        } else if (!wardChoices.isEmpty()) {
            wardVerification = "REQUIRES_VERIFICATION";
            suggested = suggestWard(body.code(), wardChoices, lat, lng, localityNames).orElse(null);
        }
        List<LocalBodyRef> bodyChoices = body == null && village != null ? village.localBodies() : List.of();
        return Optional.of(new AreaMatchResponse(state, district, body, village, ward, ward != null ? "BOUNDARY" : null,
                wardChoices, List.of(), bodyChoices, "EXACT", pincode, "BOUNDARY", suggested, wardVerification));
    }

    /**
     * The single ref of a layer among the hits. For WARD only PUBLISHED /
     * VERIFIED maps count when {@code trustedOnly}.
     */
    private static Optional<String> only(List<GeoRepository.BoundaryHit> hits, String layer, boolean trustedOnly) {
        Set<String> refs = new LinkedHashSet<>();
        for (GeoRepository.BoundaryHit h : hits) {
            if (h.layer().equals(layer) && (!trustedOnly || !"UNVERIFIED".equals(h.status()))) {
                refs.add(h.refCode());
            }
        }
        return refs.size() == 1 ? Optional.of(refs.iterator().next()) : Optional.empty();
    }

    /** Ward (of {@code wards}, the body's active wards) whose Admin-drawn boundary contains the point. */
    private Optional<WardRef> adminDrawnWard(int bodyCode, List<WardRef> wards, double lat, double lng) {
        List<WardLocator.WardShape> shapes = new ArrayList<>();
        for (GeoRepository.WardBoundary b : geo.wardBoundariesOfBody(bodyCode)) {
            WardLocator.WardShape.parse(b.wardId(), b.geojson()).ifPresent(shapes::add);
        }
        if (shapes.isEmpty()) {
            return Optional.empty();
        }
        return WardLocator.locate(shapes, lat, lng, 0)
                .flatMap(id -> wards.stream().filter(w -> w.wardId().equals(id)).findFirst());
    }

    // ------------------------------------------------------------------ ward of a point (LocationService)

    /**
     * The ward of a point inside one body, ONLY when proven by a boundary: a
     * published / verified ward map (geo_boundaries) or an Admin-drawn ward
     * boundary. Match is always BOUNDARY.
     */
    public Optional<WardHit> findWard(int bodyCode, double lat, double lng, List<String> localityNames) {
        Optional<String> mapped = only(geo.containing(lat, lng), "WARD", true);
        if (mapped.isPresent()) {
            Optional<WardRef> w = geo.activeWard(Long.parseLong(mapped.get()));
            if (w.isPresent() && Objects.equals(w.get().localBodyCode(), bodyCode)) {
                return Optional.of(new WardHit(w.get().wardId(), "BOUNDARY"));
            }
        }
        return adminDrawnWard(bodyCode, geo.wardsOfBody(bodyCode), lat, lng).map(w -> new WardHit(w.wardId(), "BOUNDARY"));
    }

    /**
     * A likely ward when none is proven - for display only (suggestedWard):
     * a ward whose listed colony name matches (LOCALITY), else the ward whose
     * map centre is nearest within app.geo.nearest-ward-max-meters (NEAREST).
     */
    public Optional<WardRef> suggestWard(int bodyCode, List<WardRef> wards, double lat, double lng,
                                         List<String> localityNames) {
        if (localityNames != null && !localityNames.isEmpty()) {
            Map<String, List<Long>> keys = geo.wardLocalityKeys(bodyCode);
            for (String name : localityNames) {
                List<Long> ids = keys.get(GeoNames.normalize(name));
                if (ids != null && new HashSet<>(ids).size() == 1) {
                    long id = ids.get(0);
                    Optional<WardRef> w = wards.stream().filter(x -> x.wardId() == id).findFirst();
                    if (w.isPresent()) {
                        return w;
                    }
                }
            }
        }
        return wards.stream()
                .filter(w -> w.centreLatitude() != null && w.centreLongitude() != null)
                .map(w -> Map.entry(w, LocationFlags.distanceMeters(lat, lng, w.centreLatitude().doubleValue(),
                        w.centreLongitude().doubleValue())))
                .filter(e -> e.getValue() <= nearestWardMaxMeters)
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    public record WardHit(long wardId, String match) {
    }

    // ------------------------------------------------------------------ 2. names (no boundary)

    /**
     * No polygon covers the point: state and district from Google's names
     * (when configured) or the nearest post office; village / town proposed
     * from Google's locality names. Never sets a ward.
     */
    AreaMatchResponse fromNames(double lat, double lng, GoogleAddress address) {
        AreaRef state = null;
        DistrictRef district = null;
        String pincode = address != null ? address.pincode() : null;
        String source = address != null ? "NAME_MATCH" : "POST_OFFICE";

        if (address != null) {
            state = bestByName(geo.states(), AreaRef::name, List.of(nullToEmpty(address.stateName())));
            if (state != null) {
                district = bestByName(geo.districts(state.code()), DistrictRef::name, address.districtNames());
            }
        }
        if (district == null) {
            Optional<Integer> fromPin = pincode == null ? Optional.empty()
                    : geo.postOffices(pincode).stream().map(GeoRepository.PostOffice::districtCode)
                    .filter(Objects::nonNull).findFirst();
            if (fromPin.isEmpty()) {
                // The nearest post office only picks the district whose lists the app
                // offers (areaSource POST_OFFICE). Its PIN is not used, and LocationService
                // never stores this district - India Post coordinates contain errors.
                fromPin = geo.nearestPostOffice(lat, lng).map(GeoRepository.NearOffice::districtCode);
                source = "POST_OFFICE";
            }
            district = fromPin.flatMap(geo::district).orElse(null);
            if (district != null && state == null) {
                state = new AreaRef(district.stateCode(), district.stateName());
            }
        }
        if (district == null) {
            return AreaMatchResponse.none(state, null);
        }

        List<String> tokens = address != null ? address.localityNames() : List.of();
        List<VillageRef> villages = geo.villagesOfDistrict(district.code());
        List<VillageRef> pinVillages = pinVillages(pincode, villages);
        Ranking r = rank(tokens, pinVillages.isEmpty() ? villages : pinVillages,
                geo.localBodies(district.code(), "URBAN"));

        VillageRef village = r.village != null && r.villageScore >= GeoNames.STRONG ? r.village : null;
        LocalBodyRef body = null;
        List<LocalBodyRef> bodyChoices = new ArrayList<>();
        if (village != null && (r.body == null || r.villageToken <= r.bodyToken
                || village.localBodies().stream().anyMatch(b -> b.code().equals(r.body.code())))) {
            if (village.localBodies().size() == 1) {
                body = village.localBodies().get(0);
            } else if (village.localBodies().isEmpty() && r.body != null && r.bodyScore >= GeoNames.STRONG) {
                body = r.body;
            } else {
                bodyChoices.addAll(village.localBodies());
            }
        } else if (r.body != null && r.bodyScore >= GeoNames.STRONG) {
            body = r.body;
            village = null;
        }

        List<VillageRef> villageChoices = new ArrayList<>();
        for (Scored<VillageRef> s : r.villageCandidates) {
            if (village == null || !s.item.code().equals(village.code())) {
                villageChoices.add(s.item);
            }
        }
        if (villageChoices.isEmpty() && village == null && !pinVillages.isEmpty()) {
            villageChoices.addAll(pinVillages.subList(0, Math.min(40, pinVillages.size())));
        }
        if (body == null && bodyChoices.isEmpty()) {
            r.bodyCandidates.forEach(s -> bodyChoices.add(s.item));
        }

        List<WardRef> wardChoices = List.of();
        WardRef ward = null;
        WardRef suggested = null;
        if (body != null) {
            wardChoices = geo.wardsOfBody(body.code());
            // no published ward map covers the point; an Admin-drawn ward boundary still proves the ward
            ward = adminDrawnWard(body.code(), wardChoices, lat, lng).orElse(null);
            if (ward == null) {
                suggested = suggestWard(body.code(), wardChoices, lat, lng, tokens).orElse(null);
            }
        }

        String confidence;
        double best = Math.max(village != null ? r.villageScore : 0, body != null && village == null ? r.bodyScore : 0);
        if (best >= 0.999) {
            confidence = "EXACT";
        } else if (best >= GeoNames.STRONG) {
            confidence = "LIKELY";
        } else if (!villageChoices.isEmpty() || !bodyChoices.isEmpty()) {
            confidence = "CHOOSE";
        } else {
            confidence = "NONE";
        }
        return new AreaMatchResponse(state, district, body, village, ward, ward != null ? "BOUNDARY" : null, wardChoices,
                villageChoices.subList(0, Math.min(40, villageChoices.size())), bodyChoices, confidence,
                address != null ? address.pincode() : null, source, suggested,
                ward != null ? "VERIFIED_BOUNDARY" : wardChoices.isEmpty() ? null : "REQUIRES_VERIFICATION");
    }

    private List<VillageRef> pinVillages(String pincode, List<VillageRef> districtVillages) {
        if (pincode == null) {
            return List.of();
        }
        Set<Integer> codes = new HashSet<>(geo.villageCodesForPincode(pincode));
        if (codes.isEmpty()) {
            return List.of();
        }
        return districtVillages.stream().filter(v -> codes.contains(v.code())).toList();
    }

    /** "Ambedkar Road, Kavi Nagar · Ward 91 - Kavi Nagar, Ghaziabad, Ghaziabad, Uttar Pradesh 201002". */
    static String addressLine(List<String> osmNames, AreaMatchResponse area) {
        StringJoiner j = new StringJoiner(", ");
        Set<String> used = new HashSet<>();
        for (String s : osmNames.subList(0, Math.min(2, osmNames.size()))) {
            if (used.add(GeoNames.normalize(s))) {
                j.add(s);
            }
        }
        if (area.ward() != null && used.add(GeoNames.normalize(area.ward().name()))) {
            j.add(area.ward().name());
        }
        if (area.village() != null && used.add(GeoNames.normalize(area.village().name()))) {
            j.add(area.village().name());
        }
        if (area.localBody() != null && used.add(GeoNames.normalize(area.localBody().name()))) {
            j.add(area.localBody().name());
        }
        // a district guessed from the nearest post office is not shown as the address
        boolean guessed = "POST_OFFICE".equals(area.areaSource());
        if (area.district() != null && !guessed) {
            j.add(area.district().name());
        }
        String line = j.toString();
        if (area.state() != null && !guessed) {
            line = line.isEmpty() ? area.state().name() : line + ", " + area.state().name();
        }
        if (area.pincode() != null) {
            line = line + " " + area.pincode();
        }
        return line.isBlank() ? null : line.trim();
    }

    // ------------------------------------------------------------------
    // Ranking - static and pure (AreaResolverTest)
    // ------------------------------------------------------------------

    record Scored<T>(T item, double score, int token) {
    }

    static final class Ranking {
        VillageRef village;
        double villageScore;
        int villageToken = Integer.MAX_VALUE;
        LocalBodyRef body;
        double bodyScore;
        int bodyToken = Integer.MAX_VALUE;
        final List<Scored<VillageRef>> villageCandidates = new ArrayList<>();
        final List<Scored<LocalBodyRef>> bodyCandidates = new ArrayList<>();
    }

    /**
     * Ranks villages and urban bodies against Google's locality names.
     * {@code tokens} are most specific first, so for equal scores the match
     * on the more specific name (lower index) wins.
     */
    static Ranking rank(List<String> tokens, List<VillageRef> villages, List<LocalBodyRef> urbanBodies) {
        Ranking r = new Ranking();
        if (tokens == null || tokens.isEmpty()) {
            return r;
        }
        List<Scored<VillageRef>> vs = new ArrayList<>();
        for (VillageRef v : villages) {
            Scored<VillageRef> s = bestScore(v, v.name(), tokens);
            if (s.score() >= GeoNames.WEAK) {
                vs.add(s);
            }
        }
        vs.sort(Comparator.<Scored<VillageRef>>comparingDouble(s -> -s.score()).thenComparingInt(Scored::token));
        if (!vs.isEmpty()) {
            // Same-named villages are common ("Jolah Bigha" exists in both Gurua and
            // Tikari of Gaya). Among equally good matches, keep the one whose
            // tehsil / block / Gram Panchayat / town also appears in Google's names;
            // if that does not single one out, propose none (the app shows the list).
            double top = vs.get(0).score();
            int topToken = vs.get(0).token();
            List<Scored<VillageRef>> tied = vs.stream()
                    .filter(s -> s.score() >= top - 1e-9 && s.token() == topToken).toList();
            Scored<VillageRef> pick = tied.size() == 1 ? tied.get(0) : null;
            if (pick == null) {
                List<Scored<VillageRef>> withContext = tied.stream().filter(s -> hasContext(s.item(), tokens)).toList();
                pick = withContext.size() == 1 ? withContext.get(0) : null;
            }
            if (pick != null) {
                r.village = pick.item();
                r.villageScore = pick.score();
                r.villageToken = pick.token();
            }
        }
        r.villageCandidates.addAll(vs.subList(0, Math.min(8, vs.size())));

        List<Scored<LocalBodyRef>> bs = new ArrayList<>();
        for (LocalBodyRef b : urbanBodies) {
            Scored<LocalBodyRef> s = bestScore(b, b.name(), tokens);
            if (s.score() >= GeoNames.WEAK) {
                bs.add(s);
            }
        }
        bs.sort(Comparator.<Scored<LocalBodyRef>>comparingDouble(s -> -s.score()).thenComparingInt(Scored::token));
        if (!bs.isEmpty()) {
            r.body = bs.get(0).item();
            r.bodyScore = bs.get(0).score();
            r.bodyToken = bs.get(0).token();
        }
        r.bodyCandidates.addAll(bs.subList(0, Math.min(5, bs.size())));
        return r;
    }

    /** A token names the village's tehsil, block, Gram Panchayat or town. */
    private static boolean hasContext(VillageRef v, List<String> tokens) {
        List<String> context = new ArrayList<>();
        context.add(v.subdistrictName());
        context.add(v.blockName());
        v.localBodies().forEach(b -> context.add(b.name()));
        for (String t : tokens) {
            for (String c : context) {
                if (c != null && GeoNames.similarity(t, c) >= GeoNames.STRONG) {
                    return true;
                }
            }
        }
        return false;
    }

    private static <T> Scored<T> bestScore(T item, String name, List<String> tokens) {
        double best = 0;
        int at = Integer.MAX_VALUE;
        for (int i = 0; i < tokens.size(); i++) {
            double s = GeoNames.similarity(tokens.get(i), name);
            if (s > best + 1e-9) {
                best = s;
                at = i;
            }
        }
        return new Scored<>(item, best, at);
    }

    /** Best item whose name matches one of {@code names} at least {@link GeoNames#STRONG}. */
    static <T> T bestByName(List<T> items, java.util.function.Function<T, String> name, List<String> names) {
        T best = null;
        double bestScore = 0;
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            for (T item : items) {
                double s = GeoNames.similarity(n, name.apply(item));
                if (s > bestScore) {
                    bestScore = s;
                    best = item;
                }
            }
            if (bestScore >= 0.999) {
                break;
            }
        }
        return bestScore >= GeoNames.STRONG ? best : null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
