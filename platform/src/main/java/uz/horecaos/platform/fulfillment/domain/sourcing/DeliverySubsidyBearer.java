package uz.horecaos.platform.fulfillment.domain.sourcing;

/**
 * Who absorbs the gap when a partner's actual delivery cost is higher than the
 * customer's snapshotted delivery fee (ADR 0014's "Customer fee and cost
 * allocation").
 *
 * <p>The customer's fee never moves once they have agreed to it — raising it
 * after checkout is the alternative ADR 0014 names and refuses by name, because
 * it changes a price the customer already accepted. So the gap goes somewhere
 * else, and this is the closed list of where: the tenant, the brand, the
 * location, or the platform absorbs it outright, or the amount is large enough
 * that nobody absorbs it without a person deciding first.
 */
public enum DeliverySubsidyBearer {
    TENANT,
    BRAND,
    LOCATION,
    PLATFORM,

    /**
     * Recorded, not yet allocated. The gap is real and is written the instant it
     * is known, but who pays it is an operator decision rather than something a
     * policy default should take silently — the shape ADR 0014's allocation list
     * itself offers as one of its five outcomes.
     */
    MANUAL_APPROVAL_REQUIRED
}
