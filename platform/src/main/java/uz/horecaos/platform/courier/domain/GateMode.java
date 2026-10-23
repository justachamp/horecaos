package uz.horecaos.platform.courier.domain;

/**
 * How firmly the pickup and the handover hold a courier to the position check (ADR 0042, ADR 0178,
 * couriers.md §16).
 *
 * <p>ADR 0042: «PICKED_UP and DELIVERED are soft by default and record GEO_UNVERIFIED: a hard gate
 * strands a courier in a stairwell with a customer waiting, and the workaround is marking delivered
 * from the street, which yields worse data and a worse delivery.» A location hardens it through
 * ADR 0030 when it would rather refuse. Accepting an offer is not governed by this: that gate is
 * hard whenever the GPS switch is on.
 */
public enum GateMode {

    /** A check that fails is recorded as failed and the step goes ahead. The default. */
    SOFT,

    /** A check that fails refuses the step. */
    HARD
}
