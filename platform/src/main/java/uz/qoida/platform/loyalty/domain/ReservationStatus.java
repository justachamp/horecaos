package uz.qoida.platform.loyalty.domain;

/** The life of one redemption hold (ADR 0046). */
public enum ReservationStatus {

    /** Points are already debited; the tender has reserved but not settled. */
    HELD,

    SETTLED,

    /** The tender never settled. Points returned with RELEASE entries. */
    RELEASED,

    /** The settled tender was refunded. Points returned with REVERSAL entries. */
    REVERSED
}
