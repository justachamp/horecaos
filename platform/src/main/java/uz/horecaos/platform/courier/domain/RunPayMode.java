package uz.horecaos.platform.courier.domain;

/**
 * How a courier is paid when one run carries several orders (ADR 0170 Decision 6).
 *
 * <p>Chosen explicitly in the {@code courier.compensation} document; {@code null} there means «not decided» and
 * behaves as {@link #PER_ORDER}. A tenant cannot enable grouping without choosing, because grouping changes
 * what a self-employed person earns for the same kilometres and ADR 0042 treats that as agreed in the
 * engagement terms, never as something the platform does to them silently.
 */
public enum RunPayMode {

    /** Every order in a run pays its full rate card, as an order delivered alone does. */
    PER_ORDER,

    /**
     * The first order of a run pays exactly as today. Each later order pays its per-order component and its
     * minimum floor in full, and its per-kilometre component on the shorter of its own distance and its
     * distance to the nearest drop-off already in the run. The basis is stamped when the offer is made, shown to
     * the courier before they accept, and never lowered afterwards.
     */
    FIRST_FULL_THEN_INCREMENT
}
