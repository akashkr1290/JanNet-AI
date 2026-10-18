package com.jannetai.backend.service.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantRateLimiterTest {

    @Test
    void limitsEachUserPerMinute() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        AssistantRateLimiter limiter = new AssistantRateLimiter(2, clock);
        limiter.check(1L);
        limiter.check(1L);
        assertThatThrownBy(() -> limiter.check(1L)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThatCode(() -> limiter.check(2L)).doesNotThrowAnyException();
        now.addAndGet(60_000L);
        assertThatCode(() -> limiter.check(1L)).doesNotThrowAnyException();
    }
}
