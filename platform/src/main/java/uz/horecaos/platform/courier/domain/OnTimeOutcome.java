package uz.horecaos.platform.courier.domain;

/** The delivery's punctuality, computed once at delivery (ADR 0042). */
public enum OnTimeOutcome {
    ON_TIME,

    /** Late, with the kitchen handover inside the plan's pickup window. */
    LATE,

    /**
     * Late because the kitchen handed over after the pickup window closed.
     * Penalising a courier for a late kitchen is how a tenant loses its
     * couriers, and the branch is the party that can fix it.
     */
    LATE_EXCUSED,

    /**
     * No promise was recorded on the plan. Earns no premium and triggers no
     * penalty: an absent promise is the platform's failure, and the honest
     * treatment of a missing input is neutral pay rather than a guess.
     */
    UNKNOWN
}
