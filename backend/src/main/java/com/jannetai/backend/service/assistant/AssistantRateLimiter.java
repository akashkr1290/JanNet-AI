package com.jannetai.backend.service.assistant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JanNet Sahayak: at most {@code app.assistant.max-messages-per-minute} messages
 * per user per minute (default 20), on top of the API-wide 100 requests/minute
 * (RateLimitingFilter). Same single-instance, in-memory fixed window as that
 * filter (ARCHITECTURE.md: no Redis on the pilot).
 */
@Component
public class AssistantRateLimiter {

    private final int limitPerMinute;
    private final Clock clock;
    private final ConcurrentHashMap<Long, long[]> windows = new ConcurrentHashMap<>();

    @Autowired
    public AssistantRateLimiter(@Value("${app.assistant.max-messages-per-minute:20}") int limitPerMinute) {
        this(limitPerMinute, Clock.systemUTC());
    }

    AssistantRateLimiter(int limitPerMinute, Clock clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    /** @throws ResponseStatusException 429 when the user sent too many messages this minute */
    public void check(Long userId) {
        long minute = clock.millis() / 60_000L;
        long[] window = windows.compute(userId, (id, w) -> {
            if (w == null || w[0] != minute) {
                return new long[] {minute, 1};
            }
            w[1]++;
            return w;
        });
        if (window[1] > limitPerMinute) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many messages - please wait a minute and try again");
        }
    }
}
