package uz.horecaos.platform.reporting.domain;

import java.util.List;

/**
 * The dimensions a metric is defined at (ADR 0043).
 *
 * <p>A metric's grain is part of its definition and not a query option, because
 * two tiles aggregating the same fact at different grains are the two answers
 * this ADR exists to prevent. A query may roll a metric <em>up</em> from its
 * grain; it may never claim a finer one.
 */
public enum Grain {

    /** Business day, tenant-wide. Operational counts only — never money. */
    DAY(List.of()),

    DAY_LOCATION(List.of(Dimension.LOCATION)),

    DAY_LOCATION_CHANNEL(List.of(Dimension.LOCATION, Dimension.CHANNEL)),

    /**
     * The money grain (ADR 0038). One tenant can trade as two companies on the
     * same evening, so a revenue or tax figure is only meaningful once the legal
     * entity is named.
     */
    DAY_LEGAL_ENTITY(List.of(Dimension.LEGAL_ENTITY)),

    DAY_LOCATION_LEGAL_ENTITY(List.of(Dimension.LOCATION, Dimension.LEGAL_ENTITY)),

    /**
     * T12 (7.5/7.5a): the operator leaderboard and receipt-depth grain. Not
     * money — a per-operator revenue cut is a bespoke bounded read (like the
     * order- and variant-grain reports beside it), never the typed {@code
     * /queries} pipeline, so nothing here needs {@link Dimension#LEGAL_ENTITY}.
     */
    DAY_LOCATION_OPERATOR(List.of(Dimension.LOCATION, Dimension.OPERATOR)),

    /**
     * P39 (7.1c/7.3b): the payment-mix grain, over {@code fact_order_tender}.
     * Money, so {@link Dimension#LEGAL_ENTITY} has to be named here too —
     * {@link uz.horecaos.platform.reporting.domain.MetricDefinition}'s own
     * constructor refuses a {@code UZS_SOM} metric at a grain that omits it.
     * Answered by its own {@code GET .../reporting/payment-mix}, not the typed
     * {@code /queries} pipeline: a share-per-method breakdown is several rows
     * per slice, the same reason {@link #DAY_LOCATION} gets one for
     * {@code sla_bucket_set.v1}.
     */
    DAY_LOCATION_LEGAL_ENTITY_PAYMENT_METHOD(
            List.of(Dimension.LOCATION, Dimension.LEGAL_ENTITY, Dimension.PAYMENT_METHOD)),

    /**
     * T13 (7.6a): the new-versus-returning revenue cut, over {@code
     * reporting.fact_order.is_first_order} directly rather than {@code
     * agg_branch_day} — the aggregate has no revenue split by first order,
     * only the {@code distinct_customers}/{@code new_customers} counts. Money,
     * so {@link Dimension#LEGAL_ENTITY} has to be named here too, the same
     * rule {@link #DAY_LOCATION_LEGAL_ENTITY_PAYMENT_METHOD} already follows.
     * Unlike that grain, this one <em>is</em> answered by the typed {@code
     * /queries} pipeline (see {@code ReportQueryService#run}'s
     * customer-type branch) — it is a single value per slice, just sourced
     * from a different fact table than every other {@code /queries} metric.
     */
    DAY_LOCATION_LEGAL_ENTITY_CUSTOMER_TYPE(
            List.of(Dimension.LOCATION, Dimension.LEGAL_ENTITY, Dimension.CUSTOMER_TYPE));

    /** The axes a reporting query may group by. */
    public enum Dimension {
        LOCATION,
        CHANNEL,
        FULFILMENT_TYPE,
        LEGAL_ENTITY,

        /**
         * T12: a human staff subject, or a pseudo-operator named after its
         * channel when no human touched the order. See {@code
         * reporting.application.OperatorAttribution}.
         */
        OPERATOR,

        /**
         * P39: {@code reporting.fact_order_tender.payment_method_code} — the
         * ADR 0038 tenant payment-method registry code, snapshotted onto the
         * tender. Never a second enum of payment types.
         */
        PAYMENT_METHOD,

        /**
         * T13 (7.6a): {@code "NEW"} or {@code "RETURNING"}, derived from
         * {@code reporting.fact_order.is_first_order}. Never a third value —
         * an order with no customer account at all (no {@code
         * customer_subject_hash}) has no first-order flag either and is
         * excluded from this dimension entirely rather than folded into
         * either bucket.
         */
        CUSTOMER_TYPE
    }

    // ImmutableEnumChecker judges by the field's declared type, which is the
    // mutable List interface, and cannot see that the constructor stores an
    // unmodifiable copy. List.copyOf below makes every instance genuinely
    // immutable (mutation throws), and dimensions() hands the same reference
    // out, so there is no path to shared mutable state for the check to catch.
    @SuppressWarnings("ImmutableEnumChecker")
    private final List<Dimension> dimensions;

    Grain(List<Dimension> dimensions) {
        this.dimensions = List.copyOf(dimensions);
    }

    public List<Dimension> dimensions() {
        return dimensions;
    }

    /**
     * Whether this grain names the legal entity.
     *
     * <p>The one question the money reports ask. A metric defined at an
     * entity-bearing grain may not be returned without that column, which is what
     * stops a multi-entity tenant being handed a combined total that reconciles to
     * neither tax filing.
     */
    public boolean namesLegalEntity() {
        return dimensions.contains(Dimension.LEGAL_ENTITY);
    }
}
