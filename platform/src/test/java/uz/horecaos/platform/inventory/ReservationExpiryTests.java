package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.inventory.domain.ReservationExpiry;

/**
 * The invariant a wired {@code inventory.reservation_ttl_seconds} must never
 * be allowed to break: a stock hold may not expire before the quote it backs
 * (ADR 0017, ADR 0018, ADR 0030).
 *
 * <p>What would still be true if this were broken: a reservation resolved
 * purely from its own configured TTL, with no regard for the quote at all.
 * {@link #aShortReservationTtlCannotReleaseStockBeforeTheQuoteExpires()} is
 * the test that fails under that broken code — a five-minute reservation TTL
 * against a fifteen-minute quote must still hold for the full fifteen, not
 * five.
 */
class ReservationExpiryTests {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    @Test
    @DisplayName("a reservation TTL shorter than the quote's own life is overridden up to the quote's expiry")
    void aShortReservationTtlCannotReleaseStockBeforeTheQuoteExpires() {
        // The trap: an operator (or two independently drifting defaults) sets
        // the reservation TTL to five minutes while the quote it is taken
        // against is still good for fifteen. A reservation that expires at
        // NOW+5m would release the item while the customer can still accept
        // the NOW+15m price -- the next customer buys what the first customer
        // already locked in. Overselling.
        Instant quoteExpiresAt = NOW.plus(Duration.ofMinutes(15));
        Instant candidateFromShortTtl = NOW.plus(Duration.ofMinutes(5));

        Instant effective = ReservationExpiry.notBefore(candidateFromShortTtl, quoteExpiresAt);

        assertThat(effective)
                .as("the hold must survive at least as long as the price it is protecting")
                .isEqualTo(quoteExpiresAt)
                .isAfter(candidateFromShortTtl);
    }

    @Test
    @DisplayName("a reservation TTL at least as long as the quote's life is left alone")
    void aReservationTtlAtLeastAsLongAsTheQuoteIsUnaffected() {
        // The floor must not lengthen a reservation that was already fine, or
        // every reservation would silently inherit whatever the quote TTL
        // happens to be regardless of its own configuration.
        Instant quoteExpiresAt = NOW.plus(Duration.ofMinutes(15));
        Instant candidateFromLongTtl = NOW.plus(Duration.ofHours(1));

        Instant effective = ReservationExpiry.notBefore(candidateFromLongTtl, quoteExpiresAt);

        assertThat(effective).isEqualTo(candidateFromLongTtl);
    }

    @Test
    @DisplayName("an exactly-equal candidate is left as is")
    void anEqualCandidateIsUnaffected() {
        Instant sameInstant = NOW.plus(Duration.ofMinutes(15));

        assertThat(ReservationExpiry.notBefore(sameInstant, sameInstant)).isEqualTo(sameInstant);
    }

    @Test
    @DisplayName("both inputs are required")
    @SuppressWarnings("NullAway")
    void bothInputsAreRequired() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> ReservationExpiry.notBefore(null, NOW)))
                .isInstanceOf(NullPointerException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> ReservationExpiry.notBefore(NOW, null)))
                .isInstanceOf(NullPointerException.class);
    }
}
