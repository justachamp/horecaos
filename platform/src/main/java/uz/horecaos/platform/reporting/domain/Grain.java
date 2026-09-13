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
    DAY_LOCATION_OPERATOR(List.of(Dimension.LOCATION, Dimension.OPERATOR));

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
        OPERATOR
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
