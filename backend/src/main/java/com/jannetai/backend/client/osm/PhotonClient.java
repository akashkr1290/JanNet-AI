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

/**
 * Photon (photon.komoot.io) - free OpenStreetMap search, no API key, built for
 * search-as-you-type and tolerant of spelling ("Republic Crossing" finds
 * "Crossings Republik"). It is the first service the map search box asks for
 * places outside our own dataset; {@link NominatimClient} is the fallback.
 * Only India is returned. Like Nominatim it never decides a complaint's area,
 * PIN or ward - those come from the published boundaries and LGD tables.
 *
 * <p>Configuration: app.geo.photon.* (application.yml). To run your own Photon
 * server later, only {@code PHOTON_BASE_URL} changes.
 */
@Component
public class PhotonClient {

    private static final Logger log = LoggerFactory.getLogger(PhotonClient.class);
    private static final int CACHE_SIZE = 2_000;
    /** India: minLon,minLat,maxLon,maxLat. */
    private static final String INDIA_BBOX = "68.0,6.5,97.5,37.1";

    private final boolean enabled;
    private final String baseUrl;
    private final String userAgent;
    private final RestTemplate http;
    private final Map<String, List<NominatimClient.OsmPlace>> cache = lruCache();

    public PhotonClient(@Value("${app.geo.photon.enabled:true}") boolean enabled,
                        @Value("${app.geo.photon.base-url:https://photon.komoot.io}") String baseUrl,
                        @Value("${app.geo.photon.user-agent:JanNetAI-civic-complaints/1.0}") String userAgent,
                        @Value("${app.geo.photon.timeout-ms:4000}") long timeoutMs,
                        RestTemplateBuilder builder) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.userAgent = userAgent;
        this.http = builder.setConnectTimeout(Duration.ofMillis(timeoutMs)).setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Places in India matching {@code text}, near ({@code nearLat}, {@code nearLng}) first when given. Empty when off or failing. */
    public List<NominatimClient.OsmPlace> search(String text, Double nearLat, Double nearLng) {
        if (!enabled || text == null || text.isBlank()) {
            return List.of();
        }
        String q = text.trim();
        String key = q.toLowerCase(Locale.ROOT) + "|" + (nearLat == null ? "" :
                String.format(Locale.ROOT, "%.1f,%.1f", nearLat, nearLng));
        List<NominatimClient.OsmPlace> hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/")
                .queryParam("q", q).queryParam("limit", 8).queryParam("lang", "en").queryParam("bbox", INDIA_BBOX);
        if (nearLat != null && nearLng != null) {
            uri.queryParam("lat", nearLat).queryParam("lon", nearLng);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, userAgent);
        try {
            JsonNode body = http.exchange(uri.build().encode().toUri(), HttpMethod.GET, new HttpEntity<>(headers),
                    JsonNode.class).getBody();
            List<NominatimClient.OsmPlace> result = parseSearch(body);
            if (body != null) { // a failed call is not cached - the next request may succeed
                cache.put(key, result);
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("Photon request failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** GeoJSON FeatureCollection -> Indian places that have a name and a position. */
    static List<NominatimClient.OsmPlace> parseSearch(JsonNode n) {
        List<NominatimClient.OsmPlace> out = new ArrayList<>();
        if (n == null || !n.path("features").isArray()) {
            return out;
        }
        for (JsonNode f : n.path("features")) {
            JsonNode p = f.path("properties");
            JsonNode c = f.path("geometry").path("coordinates");
            if (!c.isArray() || c.size() < 2 || !c.get(0).isNumber() || !c.get(1).isNumber()) {
                continue;
            }
            boolean india = "IN".equalsIgnoreCase(text(p, "countrycode")) || "India".equalsIgnoreCase(text(p, "country"));
            String name = text(p, "name") != null ? text(p, "name") : text(p, "street");
            if (!india || name == null) {
                continue;
            }
            List<String> where = new ArrayList<>();
            for (String field : new String[]{"street", "locality", "district", "city", "state"}) {
                String v = text(p, field);
                if (v != null && !v.equalsIgnoreCase(name) && !where.contains(v)) {
                    where.add(v);
                }
            }
            out.add(new NominatimClient.OsmPlace(name, where.isEmpty() ? null : String.join(", ", where),
                    c.get(1).asDouble(), c.get(0).asDouble()));
        }
        return out;
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
