package com.jannetai.backend.service.geo;

import com.jannetai.backend.config.GoogleMapsProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-day call budget for each billable Google SKU, so the pilot stays
 * inside Google's free monthly amount. Checked by GoogleMapsClient before
 * every outbound call; when a SKU's budget for today (Indian day) is used up
 * the call is skipped and the app falls back to the LGD lists - nothing is
 * ever charged and nothing breaks.
 *
 * <p>Counts live in memory: correct for the pilot's single backend instance
 * (same limitation as RateLimitingFilter). Google Cloud's own per-day quota
 * on the project is the hard limit behind this one.
 */
@Component
public class GoogleQuota {

    public enum Sku { AUTOCOMPLETE, PLACE_DETAILS, GEOCODE }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final GoogleMapsProperties properties;
    private final Clock clock;
    private final Map<Sku, Integer> used = new EnumMap<>(Sku.class);
    private LocalDate day;

    @org.springframework.beans.factory.annotation.Autowired
    public GoogleQuota(GoogleMapsProperties properties) {
        this(properties, Clock.system(IST));
    }

    GoogleQuota(GoogleMapsProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Reserves one call; false when today's budget for the SKU is used up. */
    public synchronized boolean tryAcquire(Sku sku) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        if (!today.equals(day)) {
            day = today;
            used.clear();
        }
        int count = used.getOrDefault(sku, 0);
        if (count >= limit(sku)) {
            return false;
        }
        used.put(sku, count + 1);
        return true;
    }

    /** Calls made today, for the Admin platform-status screen. */
    public synchronized Map<Sku, Integer> usedToday() {
        Map<Sku, Integer> copy = new EnumMap<>(Sku.class);
        copy.putAll(used);
        return copy;
    }

    public int limit(Sku sku) {
        return switch (sku) {
            case AUTOCOMPLETE -> properties.getDailyAutocompleteLimit();
            case PLACE_DETAILS -> properties.getDailyPlaceDetailsLimit();
            case GEOCODE -> properties.getDailyGeocodeLimit();
        };
    }
}
