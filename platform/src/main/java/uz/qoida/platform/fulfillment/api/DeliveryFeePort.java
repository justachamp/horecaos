package uz.qoida.platform.fulfillment.api;

/**
 * How pricing asks fulfillment what delivery costs (ADR 0037).
 *
 * <p>One method, because there is one total order and it is written once. Two
 * entry points would be two code paths computing two plausible fees, which is the
 * ambiguity ADR 0037 exists to close.
 */
public interface DeliveryFeePort {

    /**
     * Runs steps 1 to 6 of the resolution order and records the evidence.
     *
     * <p>Never throws for a business refusal. An address outside every zone, a
     * branch with no coordinate, and a brand with no tariff are all answers a
     * storefront has to render, and an exception would turn each of them into a
     * 500 for a customer who did nothing wrong.
     */
    ResolvedDeliveryCharge resolve(DeliveryFeeQuery query);
}
