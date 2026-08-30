package uz.qoida.platform.integration.camel.delivery.yandex;

import java.util.Set;

/**
 * Maps Yandex claim statuses onto Qoida's shipment states (ADR 0014).
 *
 * <p>The mapping is deliberately explicit rather than a prefix rule. Yandex uses
 * several statuses that look terminal and are not — {@code performer_not_found}
 * means the search failed and the claim is still open — and treating them by
 * name would strand orders.
 */
final class YandexClaimStatus {

    /** Created but not accepted: a hold, no courier is coming. */
    private static final Set<String> RESERVED = Set.of(
            "new", "estimating", "ready_for_approval");

    /** Accepted and progressing towards pickup. */
    private static final Set<String> CONFIRMED = Set.of(
            "accepted", "performer_lookup", "performer_draft", "performer_found",
            "performer_not_found", "pickup_arrived", "ready_for_pickup_confirmation", "pickuped");

    private static final Set<String> IN_TRANSIT = Set.of(
            "delivery_arrived", "ready_for_delivery_confirmation");

    private static final Set<String> DELIVERED = Set.of("delivered", "delivered_finish");

    private static final Set<String> CANCELLED = Set.of(
            "cancelled", "cancelled_with_payment", "cancelled_by_taxi", "cancelled_with_items_on_hands");

    private static final Set<String> FAILED = Set.of(
            "failed", "estimating_failed", "returning", "return_arrived",
            "ready_for_return_confirmation", "returned", "returned_finish");

    private YandexClaimStatus() {
    }

    static String toShipmentState(String providerStatus) {
        if (RESERVED.contains(providerStatus)) {
            return "RESERVED";
        }
        if (CONFIRMED.contains(providerStatus)) {
            return "CONFIRMED";
        }
        if (IN_TRANSIT.contains(providerStatus)) {
            return "IN_TRANSIT";
        }
        if (DELIVERED.contains(providerStatus)) {
            return "DELIVERED";
        }
        if (CANCELLED.contains(providerStatus)) {
            return "CANCELLED";
        }
        if (FAILED.contains(providerStatus)) {
            return "FAILED";
        }
        // An unrecognised status is surfaced, not guessed. Yandex adds statuses;
        // mapping an unknown one to DELIVERED or CANCELLED would be a silent
        // data error, whereas UNKNOWN stops the state machine visibly.
        return "UNKNOWN";
    }

    /** Whether a courier is committed, which is what makes cancelling potentially costly. */
    static boolean isLive(String providerStatus) {
        return CONFIRMED.contains(providerStatus)
                || IN_TRANSIT.contains(providerStatus)
                || DELIVERED.contains(providerStatus);
    }
}
