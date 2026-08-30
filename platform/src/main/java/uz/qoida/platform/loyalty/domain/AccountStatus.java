package uz.qoida.platform.loyalty.domain;

/** The lifecycle of a points account (ADR 0046). */
public enum AccountStatus {

    ACTIVE,

    /** Earns and holds its balance; cannot redeem. A support hold, not a closure. */
    SUSPENDED,

    /**
     * Terminal, and reached with a zero balance. Closure forfeits what is left
     * with a {@link EntryType#FORFEITURE} entry and never pays it out, which is
     * the moment the not-money constraints exist for: cashing a balance out at
     * par when nobody is watching is the single thing they prevent.
     */
    CLOSED
}
