package uz.qoida.platform.partner.domain;

/**
 * Who owns the courier and the customer promise (ADR 0040).
 *
 * <p>Spelled {@code fulfillment} rather than ADR 0040's {@code fulfilment}, to
 * match {@code fulfillment_mode}, {@code fulfillment_status_projection} and the
 * {@code fulfillment} schema this codebase already carries. One table with two
 * spellings of one word is a column somebody eventually types wrong.
 */
public enum FulfillmentAuthority {

    /** Qoida dispatches, tracks, and tells the customer where the food is. */
    QOIDA,

    /**
     * The aggregator does. Qoida's state machine narrows rather than being
     * surrendered: Qoida stays the only writer of {@code orders.status}, and
     * partner courier state is a projection stored beside it.
     */
    PARTNER
}
