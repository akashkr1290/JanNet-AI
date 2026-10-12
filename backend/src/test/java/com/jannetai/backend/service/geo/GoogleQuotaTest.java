package com.jannetai.backend.service.geo;

import com.jannetai.backend.config.GoogleMapsProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Daily Google budget per SKU, reset at Indian midnight. */
class GoogleQuotaTest {

    @Test
    void stopsAtTheDailyLimitAndResetsNextIstDay() {
        GoogleMapsProperties p = new GoogleMapsProperties();
        p.setDailyGeocodeLimit(2);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-11T18:00:00Z")); // 23:30 IST
        GoogleQuota quota = new GoogleQuota(p, clock);

        assertThat(quota.tryAcquire(GoogleQuota.Sku.GEOCODE)).isTrue();
        assertThat(quota.tryAcquire(GoogleQuota.Sku.GEOCODE)).isTrue();
        assertThat(quota.tryAcquire(GoogleQuota.Sku.GEOCODE)).isFalse();
        assertThat(quota.tryAcquire(GoogleQuota.Sku.AUTOCOMPLETE)).isTrue(); // other SKUs are separate

        clock.now = Instant.parse("2026-10-11T18:31:00Z"); // 00:01 IST next day
        assertThat(quota.tryAcquire(GoogleQuota.Sku.GEOCODE)).isTrue();
        assertThat(quota.usedToday()).containsEntry(GoogleQuota.Sku.GEOCODE, 1);
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return self.withZone(z);
                }

                @Override
                public Instant instant() {
                    return self.now;
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
