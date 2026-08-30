package uz.horecaos.platform.payments.api;

/** How much a future-discount entitlement is worth per use. */
public enum EntitlementBenefit {

    /**
     * A proportion, in basis points, of the component it applies to.
     *
     * <p>Always paired with a per-use maximum. A percentage without a cap is an
     * unbounded liability granted by one console click: 20% off a delivery fee is
     * 2 000 som and 20% off a corporate catering subtotal is 400 000, and the
     * person apologising for a cold pizza did not intend the second.
     */
    PERCENT,

    /** A fixed number of whole som, which is its own cap. */
    FIXED_AMOUNT
}
