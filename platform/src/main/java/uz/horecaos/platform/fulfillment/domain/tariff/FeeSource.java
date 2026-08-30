package uz.horecaos.platform.fulfillment.domain.tariff;

/** Where the customer's delivery fee comes from (ADR 0037). */
public enum FeeSource {

    /**
     * The tenant's own rate table. The default, and the recommendation: a
     * published, stable delivery price is worth more to conversion than perfect
     * cost recovery on the worst-priced orders.
     */
    TARIFF,

    /**
     * The provider's live quote, clamped by the tariff's own minimum and maximum.
     *
     * <p>Offered because some tenant will want pass-through and accept the
     * variability, and refused as a default because the price would then depend
     * on surge at the instant of checkout: two customers in one building pay
     * different fees, and a quote stops being reproducible from its context hash.
     * The gap between what the customer paid and what the provider billed is an
     * ADR 0014 {@code DELIVERY_COST_SUBSIDY}, never a higher fee.
     */
    PROVIDER_QUOTE
}
