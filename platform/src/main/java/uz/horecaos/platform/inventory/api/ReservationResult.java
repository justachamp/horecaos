package uz.horecaos.platform.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A hold, or the reason it was refused (ADR 0017).
 *
 * <p>Not an exception, because "the burger sold out while you were typing your
 * address" is an ordinary outcome a storefront must render, naming the item, so
 * the customer can fix their basket rather than guess.
 *
 * @param reservationId null when refused
 * @param expiresAt     when the hold lapses; matches the ADR 0018 quote TTL, so
 *                      stock is never held for a price nobody can still accept
 */
public record ReservationResult(UUID reservationId, Instant expiresAt, AvailabilityDecision refusal) {

    public boolean isHeld() {
        return reservationId != null;
    }

    public static ReservationResult held(UUID reservationId, Instant expiresAt) {
        return new ReservationResult(reservationId, expiresAt, AvailabilityDecision.allAvailable());
    }

    public static ReservationResult refused(AvailabilityDecision decision) {
        return new ReservationResult(null, null, decision);
    }
}
