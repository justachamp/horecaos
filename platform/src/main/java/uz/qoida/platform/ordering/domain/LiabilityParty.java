package uz.qoida.platform.ordering.domain;

/**
 * Who carries the cost of a cancelled order (ADR 0039).
 *
 * <p>Not a judgement made per incident. It is a property of the reason an admin
 * configured, so the write-off report adds up without anyone having to interpret
 * an operator's free text six months later.
 */
public enum LiabilityParty {

    /** The restaurant: it ran out, it refused, it was late. */
    TENANT,

    /** The customer: they changed their mind, they never answered the door. */
    CUSTOMER,

    /** The delivery partner: the courier lost it, the service never collected. */
    COURIER_PARTNER,

    /** Qoida: a pricing error, a serviceability error, a platform outage. */
    PLATFORM
}
