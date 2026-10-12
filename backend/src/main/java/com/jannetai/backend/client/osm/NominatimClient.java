package com.jannetai.backend.client.osm;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * OpenStreetMap Nominatim - free, no API key. Used for two things only:
 * <ul>
 *   <li>{@link #reverse}: road / colony names for the address line under the
 *       map pin (service/geo/AreaResolver#describePoint);</li>
 *   <li>{@link #search}: landmarks our own dataset does not list ("Republic
 *       Crossing") for the map search box (service/geo/GeoSearchService).</li>
 * </ul>
 * It never decides a complaint's area, PIN or ward - those always come from
 * the published boundaries and LGD tables (spec 2026-10-11).
 *
 * <p>Public-server usage policy (operations.osmfoundation.org/policies/nominatim):
 * at most one request per second for the whole application, an identifying
 * User-Agent, and caching of results. {@link #acquireSlot()} lets a request
 * through only when a second has passed since the previous one - a caller
 * that does not get a slot gets an empty answer (the app still works from our
 * dataset) instead of queueing. Answers are cached in memory.
 *
 * <p>Configuration: app.geo.nominatim.* (application.yml).
 */
@Component
public class NominatimClient {

    private static final Logger log = LoggerFactory.getLogger(NominatimClient.class);
    private static final int CACHE_SIZE = 5_000;
    private static final long MIN_INTERVAL_MS = 1_000;

    /** Road / colony / landmark names around a point. All fields may be null. */
    public record OsmAddress(String displayName, String road, String neighbourhood, String suburb, String placeName,
                             String postcode) {
        /** Most specific first, for the address line and colony matching. */
        public List<String> localityNames() {
            List<String> out = new ArrayList<>();
            for (String s : new String[]{placeName, neighbourhood, suburb, road}) {
                if (s != null && !s.isBlank() && !out.contains(s)) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    /** One search hit: name, wider area text and position. */
    public record OsmPlace(String name, String displayName, double latitude, double longitude) {
    }

    private final boolean enabled;
    private final String baseUrl;
    private final String email;
    private final String userAgent;
    private final RestTemplate http;
    private final Map<String, Optional<OsmAddress>> reverseCache = lruCache();
    private final Map<String, List<OsmPlace>> searchCache = lruCache();
    private long lastCallAt;

    public NominatimClient(@Value("${app.geo.nominatim.enabled:true}") boolean enabled,
                           @Value("${app.geo.nominatim.base-url:https://nominatim.openstreetmap.org}") String baseUrl,
                           @Value("${app.geo.nominatim.email:}") String email,
                           @Value("${app.geo.nominatim.user-agent:JanNetAI-civic-complaints/1.0}") String userAgent,
                           @Value("${app.geo.nominatim.timeout-ms:4000}") long timeoutMs,
                           RestTemplateBuilder builder) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.email = email;
        this.userAgent = userAgent;
        this.http = builder.setConnectTimeout(Duration.ofMillis(timeoutMs)).setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Address around a point, cached per ~11 m cell. Empty when off, busy or failing. */
    public Optional<OsmAddress> reverse(double lat, double lng) {
        if (!enabled) {
            return Optional.empty();
        }
        String key = String.format(Locale.ROOT, "%.4f,%.4f", lat, lng);
        synchronized (reverseCache) {
            Optional<OsmAddress> hit = reverseCache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        if (!acquireSlot()) {
            return Optional.empty();
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/reverse")
                .queryParam("format", "jsonv2").queryParam("lat", lat).queryParam("lon", lng)
                .queryParam("zoom", 18).queryParam("addressdetails", 1).queryParam("accept-language", "en");
        Optional<JsonNode> body = call(uri);
        Optional<OsmAddress> result = body.map(NominatimClient::parseReverse);
        if (body.isPresent()) { // a failed call is not cached - the next request may succeed
            synchronized (reverseCache) {
                reverseCache.put(key, result);
            }
        }
        return result;
    }

    /**
     * Places in India matching {@code text}, preferring the box around
     * ({@code nearLat}, {@code nearLng}) when given. Empty when off, busy or failing.
     */
    public List<OsmPlace> search(String text, Double nearLat, Double nearLng) {
        if (!enabled || text == null || text.isBlank()) {
            return List.of();
        }
        String q = text.trim();
        String key = q.toLowerCase(Locale.ROOT) + "|" + (nearLat == null ? "" :
                String.format(Locale.ROOT, "%.1f,%.1f", nearLat, nearLng));
        synchronized (searchCache) {
            List<OsmPlace> hit = searchCache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        // wait for the turn (at most ~1.5 s) instead of answering "nothing": the address
        // lookup under the pin shares the same one-request-per-second budget
        if (!awaitSlot(1_500)) {
            return List.of();
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/search")
                .queryParam("format", "jsonv2").queryParam("q", q).queryParam("countrycodes", "in")
                .queryParam("limit", 6).queryParam("accept-language", "en");
        if (nearLat != null && nearLng != null) {
            double d = 0.6; // ~65 km box: results near the map first
            uri.queryParam("viewbox", String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f",
                    nearLng - d, nearLat + d, nearLng + d, nearLat - d));
        }
        Optional<JsonNode> body = call(uri);
        List<OsmPlace> result = body.map(NominatimClient::parseSearch).orElse(List.of());
        if (body.isPresent()) { // a failed call is not cached - the next request may succeed
            synchronized (searchCache) {
                searchCache.put(key, result);
            }
        }
        return result;
    }

    private Optional<JsonNode> call(UriComponentsBuilder uri) {
        if (email != null && !email.isBlank()) {
            uri.queryParam("email", email);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, userAgent);
        try {
            return Optional.ofNullable(http.exchange(uri.build().encode().toUri(), HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody());
        } catch (RuntimeException e) {
            log.warn("Nominatim request failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** One request per second for the whole application; no waiting. */
    synchronized boolean acquireSlot() {
        long now = System.currentTimeMillis();
        if (now - lastCallAt < MIN_INTERVAL_MS) {
            return false;
        }
        lastCallAt = now;
        return true;
    }

    /** Like {@link #acquireSlot()} but waits up to {@code maxWaitMs} for the next free second. */
    boolean awaitSlot(long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (true) {
            if (acquireSlot()) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    static OsmAddress parseReverse(JsonNode n) {
        if (n == null || n.has("error")) {
            return null;
        }
        JsonNode a = n.path("address");
        String place = text(n, "name");
        return new OsmAddress(text(n, "display_name"), text(a, "road"),
                first(a, "neighbourhood", "residential", "quarter"), first(a, "suburb", "city_district"),
                place, text(a, "postcode"));
    }

    static List<OsmPlace> parseSearch(JsonNode n) {
        List<OsmPlace> out = new ArrayList<>();
        if (n == null || !n.isArray()) {
            return out;
        }
        for (JsonNode p : n) {
            try {
                double lat = Double.parseDouble(p.path("lat").asText());
                double lng = Double.parseDouble(p.path("lon").asText());
                String display = text(p, "display_name");
                String name = text(p, "name");
                if (name == null && display != null) {
                    name = display.split(",")[0].trim();
                }
                out.add(new OsmPlace(name, display, lat, lng));
            } catch (NumberFormatException ignored) {
                // a result without a position is useless for the map
            }
        }
        return out;
    }

    private static String first(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
    }

    private static <K, V> Map<K, V> lruCache() {
        return Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > CACHE_SIZE;
            }
        });
    }
}
