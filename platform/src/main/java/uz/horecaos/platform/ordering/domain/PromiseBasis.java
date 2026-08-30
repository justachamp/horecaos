package uz.horecaos.platform.ordering.domain;

/**
 * Which source governed an order's promised time (ADR 0036).
 *
 * <p>Stored alongside the promise rather than inferred from it, because the same
 * timestamp can come from four different places and they are not equally
 * trustworthy. A promise that fell back to the platform default is a
 * configuration gap somebody should fix; a promise that came from a band the
 * merchant tuned is working as intended. Without this, both look like "19:40".
 */
public enum PromiseBasis {

    /**
     * The order carries no promise at all.
     *
     * <p>Never produced by a checkout: {@link OrderPromise#assemble} always falls
     * back to {@link #PLATFORM_DEFAULT} rather than showing a customer nothing.
     * This is the basis of orders that predate the promise columns, which V0023
     * defaulted rather than backfilled — a promise invented today for an order
     * taken last month would be a fabricated commitment, and the reports that
     * measure kept promises would count it.
     *
     * <p>The console renders it as an em dash. "We do not know" and "on time" must
     * not look the same.
     */
    NOT_PROMISED,

    /** A {@code tenant.preparation_bands} row covered the instant and governed. */
    PREPARATION_BAND,

    /**
     * A dish's {@code preparation_duration_override} exceeded the band.
     *
     * <p>A kitchen cooks in parallel, so an order is ready when its slowest item
     * is: the assembly takes a maximum, never a sum. Summing would quote two
     * hours for a table of six.
     */
    ITEM_OVERRIDE,

    /**
     * No band covered the instant, so {@link OrderPromise#DEFAULT_PREP_MINUTES}
     * applied. Worth reporting on: a branch taking orders outside every band it
     * configured is usually a branch whose hours changed and whose bands did not.
     */
    PLATFORM_DEFAULT,

    /** ADR 0047. The customer chose a slot. Not yet written by anything. */
    SCHEDULED_SLOT,

    /** ADR 0039. A human set it, outranking every derivation. Not yet written. */
    OPERATOR_OVERRIDE;

    /** Whether this basis was produced by adding a duration to an instant. */
    public boolean isDerivedFromDuration() {
        return this == PREPARATION_BAND || this == ITEM_OVERRIDE || this == PLATFORM_DEFAULT;
    }
}
