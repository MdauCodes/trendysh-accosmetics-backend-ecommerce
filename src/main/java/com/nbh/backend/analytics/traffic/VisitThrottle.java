package com.nbh.backend.analytics.traffic;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps one address from filling the visit tables: past {@link #MAX_PER_WINDOW} records in {@link #WINDOW} the rest are
 * quietly not kept (the shopper notices nothing). In memory, like LoginThrottle: enough for one backend instance.
 */
@Component
public class VisitThrottle {

    static final int MAX_PER_WINDOW = 120;
    static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int SWEEP_ABOVE = 20_000;

    private record Count(int n, Instant start) {}

    private final ConcurrentHashMap<String, Count> byAddress = new ConcurrentHashMap<>();
    private final Clock clock;

    public VisitThrottle(Clock clock) {
        this.clock = clock;
    }

    /** True when this address may record one more visit or event. */
    public boolean allow(String address) {
        Instant now = Instant.now(clock);
        Count c = byAddress.merge(address == null ? "?" : address, new Count(1, now),
                (old, ignored) -> expired(old, now) ? new Count(1, now) : new Count(old.n() + 1, old.start()));
        if (byAddress.size() > SWEEP_ABOVE) {
            byAddress.entrySet().removeIf(e -> expired(e.getValue(), now));
        }
        return c.n() <= MAX_PER_WINDOW;
    }

    private static boolean expired(Count c, Instant now) {
        return now.isAfter(c.start().plus(WINDOW));
    }
}
