package uz.qoida.platform.integration.api.delivery;

/**
 * Delivery operations, declared per adapter (ADR 0014).
 *
 * <p>Capability codes rather than provider names, because ordering and
 * fulfilment must never branch on who the partner is. Verified against both
 * partners' documentation on 2026-08-20; the two that neither supports are still
 * declared, so an adapter that gains one has somewhere to say so.
 */
public enum DeliveryCapability {

    /** Non-binding price and ETA. Neither partner returns a redeemable quote id. */
    QUOTE_DELIVERY,

    /**
     * A hold that is not yet a live booking.
     *
     * <p>Yandex supports this: a created-but-unaccepted claim is not a booking,
     * so a hold may be taken while other partners are still being evaluated.
     * Noor has no equivalent — its create is immediately live.
     */
    RESERVE_SHIPMENT,

    /** Promotes a hold to a live booking. Paired with {@link #RESERVE_SHIPMENT}. */
    CONFIRM_SHIPMENT,

    /** Creates a live booking in one call. */
    CREATE_ON_DEMAND_SHIPMENT,

    /** Creates a booking for a future pickup time. Both partners support this. */
    SCHEDULE_SHIPMENT,

    /** Changes the pickup time of an existing booking. Neither partner supports it. */
    RESCHEDULE_SHIPMENT,

    /** Reports whether cancelling is free before committing to it. Yandex only. */
    QUERY_CANCELLATION_COST,

    CANCEL_SHIPMENT,
    QUERY_SHIPMENT,
    TRACK_SHIPMENT,

    /** Normalises a pushed status update. Noor pushes; Yandex is polled. */
    VERIFY_DELIVERY_WEBHOOK
}
