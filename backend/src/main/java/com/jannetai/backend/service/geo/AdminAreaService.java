package com.jannetai.backend.service.geo;

import com.jannetai.backend.client.google.GoogleAddress;
import com.jannetai.backend.client.google.GoogleMapsClient;
import com.jannetai.backend.dto.geo.AreaRef;
import com.jannetai.backend.dto.geo.DistrictRef;
import com.jannetai.backend.dto.geo.LocalBodyRef;
import com.jannetai.backend.dto.geo.PincodeAreaResponse;
import com.jannetai.backend.dto.geo.VillageRef;
import com.jannetai.backend.dto.geo.WardRef;
import com.jannetai.backend.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The lists behind every location picker - the citizen's manual flow
 * (PIN -> village -> ward) and Admin's officer / department / ward forms
 * (state -> district -> body -> ward). Served by controller/GeoController
 * (GET /api/v1/geo/...) to any logged-in user; data from GeoRepository
 * (LGD tables V34/V35) and, for a PIN's map centre, Google Geocoding.
 */
@Service
public class AdminAreaService {

    private static final int LIST_LIMIT = 50;

    private final GeoRepository geo;
    private final GoogleMapsClient google;

    public AdminAreaService(GeoRepository geo, GoogleMapsClient google) {
        this.geo = geo;
        this.google = google;
    }

    public List<AreaRef> states() {
        return geo.states();
    }

    /** Districts of a state; with no state, the districts this deployment serves. */
    public List<DistrictRef> districts(Integer stateCode) {
        return geo.districts(stateCode);
    }

    public DistrictRef district(int code) {
        return geo.district(code).orElseThrow(() -> new ResourceNotFoundException("District not found: " + code));
    }

    public List<LocalBodyRef> localBodies(int districtCode, String kind, String query) {
        String k = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase();
        if (k != null && !k.equals("RURAL") && !k.equals("URBAN")) {
            throw new IllegalArgumentException("kind must be RURAL or URBAN");
        }
        List<LocalBodyRef> bodies = geo.localBodies(districtCode, k);
        return query == null || query.isBlank() ? bodies : rankByName(bodies, LocalBodyRef::name, query);
    }

    public LocalBodyRef localBody(int code) {
        return geo.localBody(code).orElseThrow(() -> new ResourceNotFoundException("Local body not found: " + code));
    }

    public List<WardRef> wards(int localBodyCode) {
        localBody(localBodyCode);
        return geo.wardsOfBody(localBodyCode);
    }

    /** Village search inside a district, sound-alike aware ("jolhabigha" finds "Jolah Bigha"). */
    public List<VillageRef> searchVillages(int districtCode, String query) {
        List<VillageRef> all = geo.villagesOfDistrict(districtCode);
        if (query == null || query.isBlank()) {
            return all.subList(0, Math.min(LIST_LIMIT, all.size()));
        }
        return rankByName(all, VillageRef::name, query);
    }

    public List<VillageRef> villagesOfBody(int localBodyCode) {
        localBody(localBodyCode);
        return geo.villagesOfBody(localBodyCode);
    }

    public VillageRef village(int code) {
        return geo.village(code).orElseThrow(() -> new ResourceNotFoundException("Village not found: " + code));
    }

    /**
     * Manual flow step 1: "enter your PIN". District from India Post (or
     * Google), map centre from Google (or the post offices), and the
     * villages: the PIN's own list when loaded, otherwise the district's
     * villages whose names match the PIN's post offices.
     */
    public PincodeAreaResponse pincode(String pincode) {
        if (pincode == null || !pincode.matches("[1-9][0-9]{5}")) {
            throw new IllegalArgumentException("PIN code must be 6 digits");
        }
        List<GeoRepository.PostOffice> offices = geo.postOffices(pincode);
        Optional<GoogleAddress> g = google.geocodePincode(pincode);

        Integer districtCode = offices.stream().map(GeoRepository.PostOffice::districtCode).filter(Objects::nonNull)
                .findFirst().orElse(null);
        if (districtCode == null && g.isPresent()) {
            AreaRef state = AreaResolver.bestByName(geo.states(), AreaRef::name, List.of(String.valueOf(g.get().stateName())));
            if (state != null) {
                DistrictRef d = AreaResolver.bestByName(geo.districts(state.code()), DistrictRef::name, g.get().districtNames());
                districtCode = d != null ? d.code() : null;
            }
        }
        DistrictRef district = districtCode == null ? null : geo.district(districtCode).orElse(null);

        BigDecimal lat = null;
        BigDecimal lng = null;
        Optional<double[]> polygonCentre = geo.pincodeCentre(pincode);
        if (polygonCentre.isPresent()) {
            // V36: centre of the published PIN boundary
            lat = scale(polygonCentre.get()[0]);
            lng = scale(polygonCentre.get()[1]);
        } else if (g.isPresent()) {
            lat = scale(g.get().latitude());
            lng = scale(g.get().longitude());
        } else {
            List<GeoRepository.PostOffice> located = offices.stream().filter(o -> o.latitude() != null).toList();
            if (!located.isEmpty()) {
                lat = scale(located.stream().mapToDouble(o -> o.latitude().doubleValue()).average().orElseThrow());
                lng = scale(located.stream().mapToDouble(o -> o.longitude().doubleValue()).average().orElseThrow());
            }
        }

        List<String> officeNames = offices.stream().map(GeoRepository.PostOffice::name).distinct().toList();
        List<VillageRef> villages = List.of();
        List<LocalBodyRef> towns = List.of();
        boolean exact = false;
        if (district != null) {
            List<Integer> codes = geo.villageCodesForPincode(pincode);
            if (!codes.isEmpty()) {
                exact = true;
                villages = geo.villagesByCodes(codes);
            } else {
                // Branch post offices are named after villages: offer those villages first.
                Set<String> names = new LinkedHashSet<>(officeNames.stream().map(AdminAreaService::stripOfficeSuffix).toList());
                g.ifPresent(a -> names.addAll(a.localityNames()));
                villages = matchAny(geo.villagesOfDistrict(district.code()), VillageRef::name, names);
            }
            Set<String> townNames = new LinkedHashSet<>(officeNames.stream().map(AdminAreaService::stripOfficeSuffix).toList());
            g.ifPresent(a -> townNames.addAll(a.localityNames()));
            towns = matchAny(geo.localBodies(district.code(), "URBAN"), LocalBodyRef::name, townNames);
        }
        List<com.jannetai.backend.dto.geo.PincodeBodyRef> bodies = geo.pincodeBodies(pincode);
        if (district == null && !bodies.isEmpty() && bodies.get(0).localBody().districtCode() != null) {
            district = geo.district(bodies.get(0).localBody().districtCode()).orElse(null);
        }
        return new PincodeAreaResponse(pincode, district, lat, lng, exact, villages, towns, officeNames, bodies);
    }

    /** V36: PIN autocomplete (3-5 digits typed). */
    public List<com.jannetai.backend.dto.geo.PincodeSuggestion> pincodeSuggestions(String prefix) {
        if (prefix == null || !prefix.matches("[1-9][0-9]{2,5}")) {
            throw new IllegalArgumentException("Type at least the first 3 digits of the PIN code");
        }
        return geo.pincodesStartingWith(prefix, 15);
    }

    /** V36: GeoJSON geometry of a ward boundary. */
    public String wardBoundary(long wardId) {
        return geo.wardBoundaryGeoJson(wardId)
                .orElseThrow(() -> new ResourceNotFoundException("No boundary on record for ward " + wardId));
    }

    /** V36: bodies of a PIN (pincode_local_bodies). */
    public List<com.jannetai.backend.dto.geo.PincodeBodyRef> pincodeBodies(String pincode) {
        if (pincode == null || !pincode.matches("[1-9][0-9]{5}")) {
            throw new IllegalArgumentException("PIN code must be 6 digits");
        }
        return geo.pincodeBodies(pincode);
    }

    // ---------------------------------------------------------------- Admin changes

    public DistrictRef setDistrictServed(int districtCode, boolean served) {
        district(districtCode);
        geo.setDistrictServed(districtCode, served);
        geo.evictCaches();
        return district(districtCode);
    }

    public VillageRef setVillageCentre(int villageCode, BigDecimal lat, BigDecimal lng) {
        village(villageCode);
        geo.setVillageCentre(villageCode, lat, lng);
        geo.evictCaches();
        return village(villageCode);
    }

    // ---------------------------------------------------------------- helpers

    /** "Tekari S.O" / "Pakri B.O" -> "Tekari" / "Pakri". */
    static String stripOfficeSuffix(String office) {
        return office == null ? "" : office.replaceAll("(?i)\\s+(B\\.?O|S\\.?O|H\\.?O|G\\.?P\\.?O)\\.?$", "").trim();
    }

    private static <T> List<T> rankByName(List<T> items, java.util.function.Function<T, String> name, String query) {
        String key = GeoNames.normalize(query);
        record S<T>(T item, double score) {
        }
        List<S<T>> scored = new ArrayList<>();
        for (T item : items) {
            String n = GeoNames.normalize(name.apply(item));
            double s = n.startsWith(key) ? 1.0 + (key.length() / (double) Math.max(1, n.length()))
                    : n.contains(key) ? 0.97 : GeoNames.similarity(query, name.apply(item));
            if (s >= GeoNames.WEAK) {
                scored.add(new S<>(item, s));
            }
        }
        scored.sort(Comparator.comparingDouble(s -> -s.score()));
        return scored.stream().limit(LIST_LIMIT).map(S::item).toList();
    }

    private static <T> List<T> matchAny(List<T> items, java.util.function.Function<T, String> name, Set<String> names) {
        List<T> out = new ArrayList<>();
        for (T item : items) {
            for (String n : names) {
                if (!n.isBlank() && GeoNames.similarity(n, name.apply(item)) >= GeoNames.STRONG) {
                    out.add(item);
                    break;
                }
            }
        }
        return out.size() > LIST_LIMIT ? out.subList(0, LIST_LIMIT) : out;
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }
}
