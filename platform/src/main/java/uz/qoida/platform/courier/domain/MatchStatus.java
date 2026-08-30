package uz.qoida.platform.courier.domain;

/** How a partner invoice line reconciles against Qoida's shipments (ADR 0042). */
public enum MatchStatus {

    PENDING,
    MATCHED,

    /** Matched, and the amount differs from what was accrued at booking. */
    VARIANCE,

    /** Qoida has a shipment the partner never billed. */
    UNBILLED,

    /**
     * The partner billed for something Qoida has no shipment for. The direction
     * reconciliation reports usually omit, and the only one that can hide a
     * charge for a delivery that never happened. Never netted into a total.
     */
    UNMATCHED_LINE
}
