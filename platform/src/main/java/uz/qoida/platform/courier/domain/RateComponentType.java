package uz.qoida.platform.courier.domain;

/**
 * The four rate card components, and there is no fifth (ADR 0042).
 *
 * <p>No scripting, for the reason ADR 0018 gives about pricing rules: a
 * tenant-authored expression is a program nobody reviews, running against money
 * somebody is owed. The narrowness is a stated cost — the first request this
 * cannot satisfy, a weather surcharge say, is a schema and calculator change
 * rather than configuration.
 */
public enum RateComponentType {

    /** Credited once per closed shift meeting its minimum paid seconds. */
    PER_SHIFT_FIXED,

    /** Credited per delivered order. */
    PER_ORDER,

    /** Credited per kilometre of the distance falling inside a band. */
    PER_KM_BAND,

    /** A floor on one order's accrual, topped up to reach it. */
    PER_ORDER_MINIMUM
}
