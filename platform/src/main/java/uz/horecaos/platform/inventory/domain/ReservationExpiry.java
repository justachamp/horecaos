package uz.horecaos.platform.inventory.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The floor that keeps a stock hold from outliving the price it was taken
 * for (ADR 0017, ADR 0018, ADR 0030).
 *
 * <p>{@code inventory.reservation_ttl_seconds} and {@code
 * pricing.quote_ttl_seconds} were both wired to a resolver on 2026-09-10.
 * Independently settable, they let an operator configure a reservation
 * shorter than the quote it backs — inventory releases the item while the
 * price on screen is still acceptable, and the next customer who wants it
 * can be sold what the first customer already paid for. That is overselling,
 * and it is exactly the failure {@code InventoryService.RESERVATION_TTL}'s
 * own doc and {@code QuoteService.QUOTE_TTL}'s own doc each already named by
 * citing the other's TTL — this class is where that citation becomes code.
 *
 * <p>Deliberately not "resolve both keys and compare". Two independent
 * resolutions of two different keys — possibly at different scopes if a
 * tenant overrides one and not the other, possibly minutes apart since a
 * quote is priced and then checked out later — cannot be trusted to still
 * agree by the time a reservation is taken, and a configuration change
 * between the two would make even same-instant agreement meaningless. This
 * is instead a floor against one concrete fact: the specific quote's own
 * stored {@code expiresAt}, which does not change once the quote exists. A
 * reservation is never granted an expiry earlier than that fact, no matter
 * what either key is configured to.
 *
 * <p>Pure, and separate from {@code InventoryService.reserveForQuote}, for
 * the same reason {@code QuietHours.heldUntil} and {@code TrackRetentionFloor}
 * are pure and separate from their own callers: the argument worth testing
 * exhaustively has nothing to do with a database, a clock bean, or a
 * transaction.
 */
public final class ReservationExpiry {

    private ReservationExpiry() {}

    /**
     * The reservation expiry to actually store: whichever of the two is
     * later.
     *
     * @param candidateExpiry the expiry the configured reservation TTL alone
     *                        would produce ({@code now.plus(configuredTtl)})
     * @param quoteExpiresAt  the specific quote's own stored expiry — the
     *                        floor this reservation may never fall under
     */
    public static Instant notBefore(Instant candidateExpiry, Instant quoteExpiresAt) {
        Objects.requireNonNull(candidateExpiry, "A candidate expiry is required");
        Objects.requireNonNull(quoteExpiresAt, "A quote's own expiry is required");
        return candidateExpiry.isBefore(quoteExpiresAt) ? quoteExpiresAt : candidateExpiry;
    }
}
