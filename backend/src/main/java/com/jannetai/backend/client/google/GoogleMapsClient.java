package com.jannetai.backend.client.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jannetai.backend.config.GoogleMapsProperties;
import com.jannetai.backend.service.geo.GoogleQuota;
import com.jannetai.backend.service.geo.GoogleQuota.Sku;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side calls to Google Maps Platform. The app never holds the server
 * key: it calls /api/v1/geo/* (controller/GeoController), which calls this.
 *
 * <ul>
 *   <li>{@link #autocomplete} - Places API (New) {@code places:autocomplete}
 *       ("Republic Cro..." -> suggestions). Free when the session ends in a
 *       Place Details call with the same session token.</li>
 *   <li>{@link #placeDetails} - Places API (New) Place Details, Essentials
 *       fields only (id, formattedAddress, location, addressComponents);
 *       displayName would move the call to the Pro SKU, so the suggestion's
 *       mainText is used as the landmark instead.</li>
 *   <li>{@link #reverseGeocode} - Geocoding API: pin -> address (PIN code,
 *       district, village/colony names). Cached by ~11 m cell.</li>
 *   <li>{@link #geocodePincode} - Geocoding API: PIN -> its centre, to open
 *       the map in the manual flow. Cached.</li>
 * </ul>
 *
 * Every call is checked against {@link GoogleQuota} first. Any failure
 * (switched off, no key, quota used up, network, Google error) returns an
 * empty result - the caller then uses the LGD lists, so Google is never a
 * hard dependency. The key is never logged (Geocoding takes it in the URL,
 * so exceptions are logged by class name only).
 */
@Component
public class GoogleMapsClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleMapsClient.class);
    private static final String AUTOCOMPLETE_URL = "https://places.googleapis.com/v1/places:autocomplete";
    private static final String PLACE_URL = "https://places.googleapis.com/v1/places/";
    private static final String GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json";
    private static final String PLACE_FIELDS = "id,formattedAddress,location,addressComponents";
    private static final int CACHE_SIZE = 5_000;

    private final GoogleMapsProperties properties;
    private final GoogleQuota quota;
    private final RestTemplate http;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Optional<GoogleAddress>> geocodeCache = lruCache();

    public GoogleMapsClient(GoogleMapsProperties properties, GoogleQuota quota, RestTemplateBuilder builder) {
        this.properties = properties;
        this.quota = quota;
        this.http = builder
                .setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .build();
    }

    public boolean isAvailable() {
        return properties.isUsable();
    }

    /** Suggestions for what the user typed, biased (not limited) to the given point. */
    public List<PlaceSuggestion> autocomplete(String input, String sessionToken, Double biasLat, Double biasLng) {
        if (!properties.isUsable() || input == null || input.isBlank() || !quota.tryAcquire(Sku.AUTOCOMPLETE)) {
            return List.of();
        }
        ObjectNode body = json.createObjectNode()
                .put("input", input.trim())
                .put("languageCode", "en");
        body.putArray("includedRegionCodes").add("in");
        if (sessionToken != null && !sessionToken.isBlank()) {
            body.put("sessionToken", sessionToken);
        }
        if (biasLat != null && biasLng != null) {
            ObjectNode circle = body.putObject("locationBias").putObject("circle");
            circle.putObject("center").put("latitude", biasLat).put("longitude", biasLng);
            circle.put("radius", 50_000.0);
        }
        try {
            JsonNode response = http.exchange(AUTOCOMPLETE_URL, HttpMethod.POST,
                    new HttpEntity<>(body.toString(), placesHeaders(null)), JsonNode.class).getBody();
            return parseAutocomplete(response);
        } catch (RuntimeException e) {
            log.warn("Google autocomplete failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Coordinates and address of a suggestion the user picked. */
    public Optional<GoogleAddress> placeDetails(String placeId, String sessionToken) {
        if (!properties.isUsable() || placeId == null || !placeId.matches("[A-Za-z0-9_-]{10,300}")
                || !quota.tryAcquire(Sku.PLACE_DETAILS)) {
            return Optional.empty();
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(PLACE_URL + placeId).queryParam("languageCode", "en");
        if (sessionToken != null && !sessionToken.isBlank()) {
            uri.queryParam("sessionToken", sessionToken);
        }
        try {
            JsonNode response = http.exchange(uri.build().encode().toUri(), HttpMethod.GET,
                    new HttpEntity<>(placesHeaders(PLACE_FIELDS)), JsonNode.class).getBody();
            return Optional.ofNullable(parsePlace(response));
        } catch (RuntimeException e) {
            log.warn("Google place details failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Address of a map pin. Same ~11 m cell is answered from the cache. */
    public Optional<GoogleAddress> reverseGeocode(double latitude, double longitude) {
        String cacheKey = String.format(Locale.ROOT, "ll:%.4f,%.4f", latitude, longitude);
        return geocode(cacheKey, Map.of("latlng", String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude)));
    }

    /** Centre and district of a PIN code (manual flow: "enter your PIN"). */
    public Optional<GoogleAddress> geocodePincode(String pincode) {
        if (pincode == null || !pincode.matches("[1-9][0-9]{5}")) {
            return Optional.empty();
        }
        return geocode("pin:" + pincode, Map.of("components", "postal_code:" + pincode + "|country:IN"));
    }

    private Optional<GoogleAddress> geocode(String cacheKey, Map<String, String> params) {
        synchronized (geocodeCache) {
            Optional<GoogleAddress> cached = geocodeCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        if (!properties.isUsable() || !quota.tryAcquire(Sku.GEOCODE)) {
            return Optional.empty();
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(GEOCODE_URL)
                .queryParam("language", "en")
                .queryParam("region", "in")
                .queryParam("key", properties.getServerKey());
        params.forEach((name, value) -> uri.queryParam(name, value));
        Optional<GoogleAddress> result;
        try {
            URI target = uri.build().encode().toUri();
            JsonNode response = http.getForObject(target, JsonNode.class);
            String status = response == null ? "EMPTY" : response.path("status").asText();
            if (!"OK".equals(status) && !"ZERO_RESULTS".equals(status)) {
                // REQUEST_DENIED / OVER_QUERY_LIMIT: not cached, so it recovers by itself
                log.warn("Google geocode status {}", status);
                return Optional.empty();
            }
            result = Optional.ofNullable(parseGeocode(response));
        } catch (RuntimeException e) {
            log.warn("Google geocode failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        synchronized (geocodeCache) {
            geocodeCache.put(cacheKey, result);
        }
        return result;
    }

    private HttpHeaders placesHeaders(String fieldMask) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Goog-Api-Key", properties.getServerKey());
        if (fieldMask != null) {
            headers.set("X-Goog-FieldMask", fieldMask);
        }
        return headers;
    }

    // ------------------------------------------------------------------
    // Response parsing - static and pure so GoogleMapsClientTest can feed
    // recorded Google responses without any network.
    // ------------------------------------------------------------------

    static List<PlaceSuggestion> parseAutocomplete(JsonNode response) {
        List<PlaceSuggestion> out = new ArrayList<>();
        if (response == null) {
            return out;
        }
        for (JsonNode s : response.path("suggestions")) {
            JsonNode p = s.path("placePrediction");
            String id = p.path("placeId").asText("");
            if (id.isEmpty()) {
                continue; // query predictions (no place) are not offered
            }
            JsonNode fmt = p.path("structuredFormat");
            String main = fmt.path("mainText").path("text").asText(p.path("text").path("text").asText(""));
            out.add(new PlaceSuggestion(id, main, fmt.path("secondaryText").path("text").asText("")));
        }
        return out;
    }

    /** Places API (New): addressComponents[] {longText, shortText, types[]}, location {latitude, longitude}. */
    static GoogleAddress parsePlace(JsonNode place) {
        if (place == null || !place.path("location").has("latitude")) {
            return null;
        }
        Components c = new Components();
        for (JsonNode comp : place.path("addressComponents")) {
            c.add(comp.path("longText").asText(""), comp.path("types"));
        }
        return c.toAddress(place.path("formattedAddress").asText(""),
                place.path("location").path("latitude").asDouble(),
                place.path("location").path("longitude").asDouble());
    }

    /**
     * Geocoding API: results[] each with address_components[] {long_name,
     * types[]}. The first result is the most precise; later results (the
     * locality, the PIN area ...) fill parts the first one lacks, e.g. the
     * PIN code is often only on a later result for rural points.
     */
    static GoogleAddress parseGeocode(JsonNode response) {
        if (response == null || !response.path("results").isArray() || response.path("results").isEmpty()) {
            return null;
        }
        JsonNode first = response.path("results").get(0);
        Components c = new Components();
        for (JsonNode result : response.path("results")) {
            for (JsonNode comp : result.path("address_components")) {
                c.add(comp.path("long_name").asText(""), comp.path("types"));
            }
        }
        JsonNode loc = first.path("geometry").path("location");
        return c.toAddress(first.path("formatted_address").asText(""), loc.path("lat").asDouble(), loc.path("lng").asDouble());
    }

    /** Collects address parts by Google type; first value wins (most precise result first). */
    private static final class Components {
        private String pincode;
        private String state;
        private String route;
        private String place;
        private final List<String> districts = new ArrayList<>();
        private final Map<String, String> localityByType = new LinkedHashMap<>();

        private static final List<String> LOCALITY_ORDER = List.of(
                "neighborhood", "sublocality_level_3", "sublocality_level_2", "sublocality_level_1",
                "sublocality", "locality", "administrative_area_level_4");

        void add(String name, JsonNode types) {
            if (name.isBlank()) {
                return;
            }
            for (JsonNode t : types) {
                String type = t.asText();
                switch (type) {
                    case "postal_code" -> pincode = pincode == null ? name : pincode;
                    case "administrative_area_level_1" -> state = state == null ? name : state;
                    case "administrative_area_level_3", "administrative_area_level_2" -> {
                        if (!districts.contains(name)) {
                            // level_3 before level_2: the district is usually level_3 in India
                            if ("administrative_area_level_3".equals(type)) {
                                districts.add(0, name);
                            } else {
                                districts.add(name);
                            }
                        }
                    }
                    case "route" -> route = route == null ? name : route;
                    case "premise", "point_of_interest", "establishment" -> place = place == null ? name : place;
                    default -> {
                        if (LOCALITY_ORDER.contains(type)) {
                            localityByType.putIfAbsent(type, name);
                        }
                    }
                }
            }
        }

        GoogleAddress toAddress(String formatted, double lat, double lng) {
            List<String> localities = new ArrayList<>();
            for (String type : LOCALITY_ORDER) {
                String name = localityByType.get(type);
                if (name != null && !localities.contains(name)) {
                    localities.add(name);
                }
            }
            String pin = pincode != null && pincode.replace(" ", "").matches("[1-9][0-9]{5}") ? pincode.replace(" ", "") : null;
            return new GoogleAddress(formatted, lat, lng, pin, state, List.copyOf(districts), List.copyOf(localities), route, place);
        }
    }

    private static <K, V> Map<K, V> lruCache() {
        return new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }
}
