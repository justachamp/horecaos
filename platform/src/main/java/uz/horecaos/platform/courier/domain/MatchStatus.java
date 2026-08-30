package uz.horecaos.platform.courier.domain;

/** How a partner invoice line reconciles against HorecaOS's shipments (ADR 0042). */
public enum MatchStatus {

    PENDING,
    MATCHED,

    /** Matched, and the amount differs from what was accrued at booking. */
    VARIANCE,

    /** HorecaOS has a shipment the partner never billed. */
    UNBILLED,

    /**
     * The partner billed for something HorecaOS has no shipment for. The direction
     * reconciliation reports usually omit, and the only one that can hide a
     * charge for a delivery that never happened. Never netted into a total.
     */
    UNMATCHED_LINE
}
