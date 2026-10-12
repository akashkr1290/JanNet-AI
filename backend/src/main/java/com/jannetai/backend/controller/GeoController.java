package com.jannetai.backend.controller;

import com.jannetai.backend.client.google.GoogleMapsClient;
import com.jannetai.backend.client.google.PlaceSuggestion;
import com.jannetai.backend.dto.geo.AreaRef;
import com.jannetai.backend.dto.geo.DistrictRef;
import com.jannetai.backend.dto.geo.GeoAddressResponse;
import com.jannetai.backend.dto.geo.LocalBodyRef;
import com.jannetai.backend.dto.geo.PincodeAreaResponse;
import com.jannetai.backend.dto.geo.VillageRef;
import com.jannetai.backend.dto.geo.WardRef;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.service.geo.AdminAreaService;
import com.jannetai.backend.service.geo.AreaResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Location look-ups for the app's map, search and pickers. Logged-in users
 * only (SecurityConfig: /api/v1/geo/** authenticated) because the Google
 * calls behind autocomplete / places / reverse cost quota; the general
 * RateLimitingFilter (100 requests/minute) applies too.
 *
 * <p>Called from Flutter core/api/geo_api.dart:
 * <ul>
 *   <li>search box: {@code /autocomplete} while typing, then {@code /places/{id}}
 *       when a suggestion is tapped (same {@code session} token - Google bills
 *       the pair as one session);</li>
 *   <li>pin dropped / moved: {@code /reverse};</li>
 *   <li>manual flow and Admin forms: {@code /pincodes/{pin}}, {@code /states},
 *       {@code /districts}, {@code /local-bodies}, {@code /villages},
 *       {@code /local-bodies/{code}/wards}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/geo")
@RequiredArgsConstructor
@Tag(name = "Geo", description = "Map search, address of a pin, and LGD area lists (state/district/body/village/ward)")
public class GeoController {

    private final GoogleMapsClient google;
    private final AreaResolver areaResolver;
    private final AdminAreaService areas;
    private final com.jannetai.backend.service.geo.GeoSearchService search;

    /**
     * V36: the map search box without an API key - PIN, our wards / post
     * offices / villages, then OpenStreetMap landmarks. {@code lat}/{@code lng}
     * (the map centre) only bias landmark results.
     */
    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public List<com.jannetai.backend.dto.geo.PlaceResult> search(@RequestParam String q,
                                                              @RequestParam(required = false) Double lat,
                                                              @RequestParam(required = false) Double lng) {
        return search.search(q, lat, lng);
    }

    /** V36: PIN autocomplete - PINs in our data starting with 3 to 5 typed digits. */
    @GetMapping("/pincodes")
    @PreAuthorize("isAuthenticated()")
    public List<com.jannetai.backend.dto.geo.PincodeSuggestion> pincodes(@RequestParam String prefix) {
        return areas.pincodeSuggestions(prefix);
    }

    /** V36: every Gram Panchayat / urban body LGD links to the PIN (many-to-many), with source + status. */
    @GetMapping("/pincodes/{pincode}/local-bodies")
    @PreAuthorize("isAuthenticated()")
    public List<com.jannetai.backend.dto.geo.PincodeBodyRef> pincodeBodies(@PathVariable String pincode) {
        return areas.pincodeBodies(pincode);
    }

    /**
     * V36: the area of a point without any address look-up - boundaries only
     * (ward only when a published ward map contains the point).
     */
    @GetMapping("/locate")
    @PreAuthorize("isAuthenticated()")
    public com.jannetai.backend.dto.geo.AreaMatchResponse locate(@RequestParam double lat, @RequestParam double lng) {
        requireIndia(lat, lng);
        return areaResolver.resolve(lat, lng, null);
    }

    /** Empty list when Google is off or today's quota is used up - the app then shows the PIN / village lists. */
    @GetMapping("/autocomplete")
    @PreAuthorize("isAuthenticated()")
    public List<PlaceSuggestion> autocomplete(@RequestParam String q,
                                              @RequestParam(required = false) String session,
                                              @RequestParam(required = false) Double lat,
                                              @RequestParam(required = false) Double lng) {
        if (q.length() > 120) {
            throw new IllegalArgumentException("Search text is too long");
        }
        return google.autocomplete(q, session, lat, lng);
    }

    /** {@code landmark}: the suggestion's main text, shown as the place name (keeps Place Details on the cheaper SKU). */
    @GetMapping("/places/{placeId}")
    @PreAuthorize("isAuthenticated()")
    public GeoAddressResponse place(@PathVariable String placeId,
                                    @RequestParam(required = false) String session,
                                    @RequestParam(required = false) String landmark) {
        return areaResolver.describePlace(placeId, session, landmark)
                .orElseThrow(() -> new ResourceNotFoundException("Place details are not available right now"));
    }

    @GetMapping("/reverse")
    @PreAuthorize("isAuthenticated()")
    public GeoAddressResponse reverse(@RequestParam double lat, @RequestParam double lng) {
        requireIndia(lat, lng);
        return areaResolver.describePoint(lat, lng);
    }

    @GetMapping("/pincodes/{pincode}")
    @PreAuthorize("isAuthenticated()")
    public PincodeAreaResponse pincode(@PathVariable String pincode) {
        return areas.pincode(pincode);
    }

    @GetMapping("/states")
    @PreAuthorize("isAuthenticated()")
    public List<AreaRef> states() {
        return areas.states();
    }

    /** {@code stateCode} omitted = the districts this deployment serves. */
    @GetMapping("/districts")
    @PreAuthorize("isAuthenticated()")
    public List<DistrictRef> districts(@RequestParam(required = false) Integer stateCode) {
        return areas.districts(stateCode);
    }

    /** {@code kind}: RURAL (Gram Panchayats) or URBAN (Nagar Nigam / Parishad ...), optional. */
    @GetMapping("/local-bodies")
    @PreAuthorize("isAuthenticated()")
    public List<LocalBodyRef> localBodies(@RequestParam int districtCode,
                                          @RequestParam(required = false) String kind,
                                          @RequestParam(required = false) String q) {
        return areas.localBodies(districtCode, kind, q);
    }

    /** V36: the ward's outline (GeoJSON geometry, lon/lat) for the map; 404 when no boundary is on record. */
    @GetMapping(value = "/wards/{wardId}/boundary", produces = "application/geo+json")
    @PreAuthorize("isAuthenticated()")
    public String wardBoundary(@PathVariable long wardId) {
        return areas.wardBoundary(wardId);
    }

    @GetMapping("/local-bodies/{code}/wards")
    @PreAuthorize("isAuthenticated()")
    public List<WardRef> wards(@PathVariable int code) {
        return areas.wards(code);
    }

    @GetMapping("/local-bodies/{code}/villages")
    @PreAuthorize("isAuthenticated()")
    public List<VillageRef> villagesOfBody(@PathVariable int code) {
        return areas.villagesOfBody(code);
    }

    @GetMapping("/villages")
    @PreAuthorize("isAuthenticated()")
    public List<VillageRef> villages(@RequestParam int districtCode, @RequestParam(required = false) String q) {
        return areas.searchVillages(districtCode, q);
    }

    @GetMapping("/villages/{code}")
    @PreAuthorize("isAuthenticated()")
    public VillageRef village(@PathVariable int code) {
        return areas.village(code);
    }

    private static void requireIndia(double lat, double lng) {
        if (lat < 6 || lat > 38 || lng < 68 || lng > 98) {
            throw new IllegalArgumentException("The point is outside India");
        }
    }
}
