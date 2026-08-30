package uz.qoida.platform.loyalty.domain;

/** Where a lot is between grant and disappearance (ADR 0046). */
public enum LotStatus {

    /** Granted, not yet spendable: the earn delay has not elapsed. */
    PENDING,

    ACTIVE,

    CONSUMED,

    EXPIRED,

    FORFEITED
}
