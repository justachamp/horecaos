package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What can be said about a tenant's card on file without saying which card it
 * is (ADR 0095, ADR 0028): the last four digits, the brand and the month it
 * lapses, as the provider's hosted form reported them when the tenant bound it.
 *
 * <p>Never the token reference, which belongs in PostgreSQL and the adapter and
 * nowhere a screen or a log line can reach. A card a staff member typed a
 * reference for has none of this, which is {@link #unknown()}: the card is
 * charged all the same, but nothing can be shown about it.
 */
public record CardOnFile(
        @Nullable String last4,
        @Nullable String brand,
        @Nullable Integer expiryMonth,
        @Nullable Integer expiryYear,
        @Nullable Instant boundAt,
        @Nullable String boundBy) {

    /** A card exists and nothing is known about it beyond that. */
    public static CardOnFile unknown() {
        return new CardOnFile(null, null, null, null, null, null);
    }

    /** True from the first instant of the month after the one it lapses in. */
    public boolean lapsedBy(Instant now) {
        return expiryMonth != null
                && expiryYear != null
                && !java.time.YearMonth.of(expiryYear, expiryMonth)
                        .atEndOfMonth()
                        .plusDays(1)
                        .atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant()
                        .isAfter(now);
    }

    /** True while the card has not lapsed but will have by {@code horizonEnd}. */
    public boolean lapsesBefore(Instant now, Instant horizonEnd) {
        return expiryMonth != null && !lapsedBy(now) && lapsedBy(horizonEnd);
    }
}
