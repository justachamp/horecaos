package uz.qoida.platform.partner.domain;

/**
 * Where an order was placed (ADR 0040).
 *
 * <p>Not derivable from the channel, which is why it is a column. A tenant may
 * register several marketplace channels, and ADR 0036 lets a channel be
 * reconfigured after orders were taken on it; reading the channel to decide what
 * an order was would make a nine-month-old order change its nature when somebody
 * edits a checkbox.
 */
public enum OrderOrigin {

    /** A Qoida surface: storefront, bot, kiosk, operator, QR table. */
    QOIDA,

    /**
     * An aggregator's order, whether it arrived through the partner API or an
     * operator keyed it in on an unintegrated partner's behalf. The customer is
     * the aggregator's; the seller is still the restaurant.
     */
    MARKETPLACE
}
