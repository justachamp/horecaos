package uz.qoida.platform.fulfillment.api;

/**
 * Who decided the price of this order (ADR 0040, consumed by ADR 0037).
 *
 * <p>Step 1 of fee resolution, and the only thing consulted before it. ADR 0037
 * originally spent a third {@code fee_source} on the same case and then withdrew
 * it, because a tariff column is the wrong place for the gate: it makes the
 * answer depend on which tariff the zone happened to resolve to, so an
 * externally-priced order reaching a zone whose tariff says {@code TARIFF} would
 * be charged a Qoida-computed fee on top of the fee the aggregator already
 * collected — and the order would still reconcile against its own stated total.
 */
public enum PricingAuthority {

    /** Qoida prices the order, so the delivery fee is resolved here. */
    QOIDA,

    /**
     * The partner priced it. Uzum Tezkor sets its own delivery price and that
     * price arrives inside the totals the partner supplied, as a fee line Qoida
     * stores verbatim and never resolves.
     */
    EXTERNAL
}
