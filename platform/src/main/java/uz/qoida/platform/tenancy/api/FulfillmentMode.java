package uz.qoida.platform.tenancy.api;

/**
 * How an order leaves the location (ADR 0036, matching ADR 0019's cart).
 *
 * <p>Dine-in is a fulfilment mode and never a channel. Delever's <i>Зал</i> is
 * both, which is why its order-type and channel filters disagree: a QR-table
 * order and a waiter-entered order are both {@link #DINE_IN} arriving through
 * different channels.
 */
public enum FulfillmentMode {

    DELIVERY,
    PICKUP,
    DINE_IN
}
