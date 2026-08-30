package uz.horecaos.platform.commercial.api;

import java.time.Instant;
import java.util.Objects;

/**
 * The window a usage figure is measured over (ADR 0021).
 *
 * <p>{@code key} is the stored partition of the ledger — {@code 2026-08},
 * {@code 2026-08-23}, or {@code LIFETIME} — and is computed from the tenant's
 * timezone rather than from UTC. A tenant in Tashkent whose month rolls at 19:00
 * the previous day sees a monthly allowance reset five hours early and an
 * invoice that disagrees with its own order list.
 *
 * @param key   the stored period partition
 * @param start inclusive
 * @param end   exclusive
 */
public record UsagePeriod(String key, Instant start, Instant end) {

    /** The single period of a standing limit, which never closes. */
    public static final String LIFETIME = "LIFETIME";

    public UsagePeriod {
        Objects.requireNonNull(key, "A period key is required");
        Objects.requireNonNull(start, "A period start is required");
        Objects.requireNonNull(end, "A period end is required");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("A period ends after it starts: " + key);
        }
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    public boolean isLifetime() {
        return LIFETIME.equals(key);
    }
}
