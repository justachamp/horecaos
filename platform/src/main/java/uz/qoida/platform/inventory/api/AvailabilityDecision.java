package uz.qoida.platform.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Whether a cart can be fulfilled here (ADR 0017).
 *
 * <p>Names every unavailable item rather than answering a bare no. A customer
 * told "something in your basket is unavailable" has to guess; one told which
 * dish can remove it and carry on.
 */
public record AvailabilityDecision(boolean available, List<Unavailable> unavailableItems) {

    public static AvailabilityDecision allAvailable() {
        return new AvailabilityDecision(true, List.of());
    }

    public static AvailabilityDecision blockedBy(List<Unavailable> items) {
        return new AvailabilityDecision(false, List.copyOf(items));
    }

    /** @param reason a stable code an operator UI can translate */
    public record Unavailable(UUID variantId, String reason) {

        public static Unavailable soldOut(UUID variantId) {
            return new Unavailable(variantId, "SOLD_OUT");
        }

        /** No stock item exists, which usually means the location never listed it. */
        public static Unavailable notStocked(UUID variantId) {
            return new Unavailable(variantId, "NOT_STOCKED_AT_LOCATION");
        }

        /**
         * The hold taken for this cart lapsed before checkout reached it.
         *
         * <p>Not attributable to one dish — the whole hold expired — so it is
         * reported against every line rather than invented against one. The
         * customer's fix is to re-price, which is a different instruction from
         * "remove this item".
         */
        public static Unavailable holdExpired(UUID variantId) {
            return new Unavailable(variantId, "RESERVATION_NO_LONGER_HELD");
        }
    }
}
