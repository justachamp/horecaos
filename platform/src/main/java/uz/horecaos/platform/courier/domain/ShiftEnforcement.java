package uz.horecaos.platform.courier.domain;

/**
 * Whether an open shift is required before a courier may be offered work
 * (ADR 0030 policy {@code courier.shift.enforcement}, ADR 0042).
 *
 * <p>The resolved value and its policy version are snapshotted onto the shift.
 * Without the snapshot, tightening the policy in October makes September's
 * shifts look illegal.
 */
public enum ShiftEnforcement {

    /** No open shift, no offer. */
    ENFORCED,

    /** The gate is computed and reported, and refuses nothing. Roll out here. */
    ADVISORY,

    /** A pure gig model: anyone eligible may take anything. */
    OFF
}
