package com.jannetai.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code app.google-maps.*} (application.yml). Read by
 * client/google/GoogleMapsClient and service/geo/GoogleQuota.
 *
 * <p>{@code serverKey} is the SERVER key (Places API (New) + Geocoding API),
 * restricted in Google Cloud to those two APIs. It comes from the
 * GOOGLE_MAPS_SERVER_KEY environment variable (pilot: SSM parameter
 * /jannet-ai/pilot-free/GOOGLE_MAPS_SERVER_KEY) and is never sent to the
 * app. The map key the app uses is a different, browser/Android-restricted
 * key (Flutter build setting GOOGLE_MAPS_BROWSER_KEY).
 *
 * <p>The daily limits keep usage inside Google's free monthly amount
 * (India: 70,000 per SKU / month): 2,000 x 31 days = 62,000. Google Cloud
 * also has the same per-day quota set on the project, so even a bug here
 * cannot create a bill.
 */
@ConfigurationProperties(prefix = "app.google-maps")
public class GoogleMapsProperties {

    /** false (default) = no Google calls; search/address come back empty and the app falls back to lists. */
    private boolean enabled = false;
    private String serverKey = "";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;

    /** Per-day caps (IST day), counted by GoogleQuota. */
    private int dailyAutocompleteLimit = 10_000;   // free when the session ends in a Place Details call
    private int dailyPlaceDetailsLimit = 2_000;
    private int dailyGeocodeLimit = 2_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerKey() {
        return serverKey;
    }

    public void setServerKey(String serverKey) {
        this.serverKey = serverKey;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getDailyAutocompleteLimit() {
        return dailyAutocompleteLimit;
    }

    public void setDailyAutocompleteLimit(int dailyAutocompleteLimit) {
        this.dailyAutocompleteLimit = dailyAutocompleteLimit;
    }

    public int getDailyPlaceDetailsLimit() {
        return dailyPlaceDetailsLimit;
    }

    public void setDailyPlaceDetailsLimit(int dailyPlaceDetailsLimit) {
        this.dailyPlaceDetailsLimit = dailyPlaceDetailsLimit;
    }

    public int getDailyGeocodeLimit() {
        return dailyGeocodeLimit;
    }

    public void setDailyGeocodeLimit(int dailyGeocodeLimit) {
        this.dailyGeocodeLimit = dailyGeocodeLimit;
    }

    /** Usable only when switched on AND a key is configured. */
    public boolean isUsable() {
        return enabled && serverKey != null && !serverKey.isBlank();
    }
}
