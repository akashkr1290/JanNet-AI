package com.jannetai.backend.service.geo;

import com.jannetai.backend.client.osm.NominatimClient;
import com.jannetai.backend.client.osm.PhotonClient;
import com.jannetai.backend.dto.geo.PlaceResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The map search box without any API key (GET /api/v1/geo/search):
 * <ol>
 *   <li>a 6-digit PIN -> the PIN's centre (published PIN boundary, else its
 *       post offices);</li>
 *   <li>our own data in the served districts - wards, post offices, villages
 *       (GeoRepository#searchPlaces);</li>
 *   <li>OpenStreetMap places anywhere in India (landmarks, societies, roads),
 *       only when our data has fewer than {@link #OSM_BELOW} results: Photon
 *       first (made for type-as-you-type, spelling tolerant), Nominatim when
 *       Photon finds nothing or is down.</li>
 * </ol>
 * A result only moves the pin; the area is then decided by /geo/reverse
 * (boundaries), never by the search text.
 */
@Service
public class GeoSearchService {

    static final int LIMIT = 10;
    static final int OSM_BELOW = 4;

    private final GeoRepository geo;
    private final NominatimClient osm;
    private PhotonClient photon; // optional: without it only Nominatim is used

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPhoton(PhotonClient photon) {
        this.photon = photon;
    }

    public GeoSearchService(GeoRepository geo, NominatimClient osm) {
        this.geo = geo;
        this.osm = osm;
    }

    public List<PlaceResult> search(String text, Double nearLat, Double nearLng) {
        String q = text == null ? "" : text.trim();
        if (q.length() < 2) {
            return List.of();
        }
        if (q.length() > 120) {
            throw new IllegalArgumentException("Search text is too long");
        }
        List<PlaceResult> out = new ArrayList<>();
        if (q.matches("[1-9][0-9]{5}")) {
            pincodeCentre(q).ifPresent(out::add);
        }
        out.addAll(geo.searchPlaces(q, LIMIT));
        boolean photonOn = photon != null && photon.isEnabled();
        if (out.size() < OSM_BELOW && (photonOn || (osm != null && osm.isEnabled())) && !q.matches("[0-9 ]+")) {
            List<NominatimClient.OsmPlace> places = photonOn ? photon.search(q, nearLat, nearLng) : List.of();
            if (places.isEmpty() && osm != null && osm.isEnabled()) {
                places = osm.search(q, nearLat, nearLng);
            }
            for (NominatimClient.OsmPlace p : places) {
                out.add(new PlaceResult(p.name(), p.displayName(), p.latitude(), p.longitude(), "PLACE", "OSM"));
            }
        }
        // same label + position twice (e.g. a ward and a post office of one name): keep one
        Set<String> seen = new HashSet<>();
        List<PlaceResult> unique = new ArrayList<>();
        for (PlaceResult r : out) {
            String key = GeoNames.normalize(r.label()) + "@" + Math.round(r.latitude() * 1000) + "," + Math.round(r.longitude() * 1000);
            if (seen.add(key)) {
                unique.add(r);
            }
        }
        return unique.size() > LIMIT ? unique.subList(0, LIMIT) : unique;
    }

    private Optional<PlaceResult> pincodeCentre(String pin) {
        Optional<double[]> c = geo.pincodeCentre(pin);
        if (c.isEmpty()) {
            List<GeoRepository.PostOffice> located = geo.postOffices(pin).stream().filter(o -> o.latitude() != null).toList();
            if (!located.isEmpty()) {
                c = Optional.of(new double[]{
                        located.stream().map(GeoRepository.PostOffice::latitude).mapToDouble(BigDecimal::doubleValue).average().orElseThrow(),
                        located.stream().map(GeoRepository.PostOffice::longitude).mapToDouble(BigDecimal::doubleValue).average().orElseThrow()});
            }
        }
        String office = geo.postOffices(pin).stream().map(GeoRepository.PostOffice::name).findFirst().orElse(null);
        return c.map(x -> new PlaceResult("PIN " + pin, office != null ? AdminAreaService.stripOfficeSuffix(office) : null,
                x[0], x[1], "PINCODE", "DATASET"));
    }
}
